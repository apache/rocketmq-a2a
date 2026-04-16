"""FastAPI routes for Supervisor Agent"""
import json
import uuid
import time
import asyncio
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse
from sse_starlette.sse import EventSourceResponse

from common.mq_toos import logger
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.workflow.workflow_graph import build_workflow

router = APIRouter()
app_graph = build_workflow()

# Get the project root directory
PROJECT_ROOT = Path(__file__).parent.parent
STATIC_FILE = PROJECT_ROOT / "static" / "index.html"


@router.get("/", response_class=HTMLResponse)
async def read_root():
    """Serve frontend HTML page"""
    with open(STATIC_FILE, "r", encoding="utf-8") as f:
        return f.read()


@router.post("/chat")
async def chat(request: dict):
    """Chat endpoint with SSE streaming support"""
    user_input = request.get("message")
    main_trace_id = str(uuid.uuid4())

    initial_state = {
        "trace_id": main_trace_id,
        "user_input": user_input,
        "intent": "",
        "city": "",
        "date_info": "",
        "weather_data": "",
        "final_response": "",
        "weather_trace_id": None,
        "travel_trace_id": None,
        "weather_complete": False
    }

    response_queue = stream_queue_manager.register_trace(main_trace_id)

    async def event_generator():
        yield {"data": json.dumps({"type": "start", "trace_id": main_trace_id})}

        try:
            active_traces = set()
            completed_traces = set()
            is_chat_mode = False

            async def run_graph():
                nonlocal is_chat_mode
                try:
                    async for event in app_graph.astream(initial_state):
                        for node_name, output in event.items():
                            logger.info(f"Graph node executed: {node_name}, output: {output}")

                            if node_name == "chat_node":
                                is_chat_mode = True
                                logger.info("Detected chat mode, will wait for streaming completion")

                            if "weather_trace_id" in output and output["weather_trace_id"]:
                                weather_tid = output["weather_trace_id"]
                                active_traces.add(weather_tid)
                                stream_queue_manager.register_sub_trace(weather_tid, main_trace_id)
                                logger.info(f"Registered weather trace: {weather_tid} -> {main_trace_id}")

                            if "travel_trace_id" in output and output["travel_trace_id"]:
                                travel_tid = output["travel_trace_id"]
                                active_traces.add(travel_tid)
                                stream_queue_manager.register_sub_trace(travel_tid, main_trace_id)
                                logger.info(f"Registered travel trace: {travel_tid} -> {main_trace_id}")

                except Exception as e:
                    logger.error(f"Graph execution error: {e}", exc_info=True)

            graph_task = asyncio.create_task(run_graph())

            stream_start_time = time.time()
            max_timeout = 120.0
            chat_stream_completed = False

            while True:
                chat_response = stream_queue_manager.get_chat_response(main_trace_id)
                if chat_response:
                    logger.info(f"Sending chat response: {chat_response}")
                    yield {"data": json.dumps({
                        "type": "chunk",
                        "role": "assistant",
                        "content": chat_response,
                        "chunk_index": 0,
                        "is_final": True,
                        "sub_trace_id": main_trace_id
                    })}
                    break

                try:
                    remaining_timeout = max_timeout - (time.time() - stream_start_time)
                    if remaining_timeout <= 0:
                        logger.warning(f"Overall timeout reached for trace_id: {main_trace_id}")
                        yield {"data": json.dumps({"type": "error", "content": "响应超时"})}
                        break

                    payload = await asyncio.wait_for(response_queue.get(), timeout=min(remaining_timeout, 5.0))

                    metadata = payload.metadata or {}
                    is_final = metadata.get("is_final", False)
                    is_error = metadata.get("error", False)
                    chunk_index = metadata.get("chunk_index", 0)

                    sub_trace_id = payload.trace_id

                    role = payload.role.value if hasattr(payload.role, 'value') else str(payload.role)

                    if is_error:
                        yield {"data": json.dumps({
                            "type": "error",
                            "role": role,
                            "content": payload.content,
                            "chunk_index": chunk_index,
                            "sub_trace_id": sub_trace_id
                        })}
                        completed_traces.add(sub_trace_id)
                        continue

                    if payload.content:
                        yield {"data": json.dumps({
                            "type": "chunk",
                            "role": role,
                            "content": payload.content,
                            "chunk_index": chunk_index,
                            "is_final": is_final,
                            "sub_trace_id": sub_trace_id
                        })}

                    if is_final:
                        if sub_trace_id != main_trace_id:
                            completed_traces.add(sub_trace_id)
                            logger.info(
                                f"Sub-trace completed: {sub_trace_id}, total completed: {len(completed_traces)}/{len(active_traces)}")

                            if active_traces and completed_traces >= active_traces:
                                logger.info(f"All sub-traces completed for main trace: {main_trace_id}")
                                break
                        else:
                            logger.info(f"Chat streaming completed for main trace: {main_trace_id}")
                            chat_stream_completed = True
                            break

                except asyncio.TimeoutError:
                    chat_response = stream_queue_manager.get_chat_response(main_trace_id)
                    if chat_response:
                        yield {"data": json.dumps({
                            "type": "chunk",
                            "role": "assistant",
                            "content": chat_response,
                            "chunk_index": 0,
                            "is_final": True,
                            "sub_trace_id": main_trace_id
                        })}
                        break

                    if chat_stream_completed:
                        logger.info("Chat stream already completed, exiting loop")
                        break

                    elif not graph_task.done() and not active_traces:
                        continue
                    elif active_traces and completed_traces >= active_traces:
                        break
                    elif time.time() - stream_start_time > max_timeout:
                        logger.warning(f"Timeout waiting for messages for trace_id: {main_trace_id}")
                        yield {"data": json.dumps({"type": "error", "content": "响应超时"})}
                        break

            try:
                await asyncio.wait_for(graph_task, timeout=10.0)
            except asyncio.TimeoutError:
                logger.warning("Graph task timeout, continuing...")

        except Exception as e:
            logger.error(f"Event generator error: {e}", exc_info=True)
            yield {"data": json.dumps({"type": "error", "content": str(e)})}
        finally:
            stream_queue_manager.unregister_trace(main_trace_id, response_queue)
            yield {"data": "[DONE]"}

    return EventSourceResponse(event_generator())
