"""FastAPI routes for Supervisor Agent"""
import json
import uuid
import time
import asyncio
from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse
from sse_starlette.sse import EventSourceResponse

from common.mq_toos import logger
from supervisor_agent.utils.rocketmq.mq_service import subscribe_lite_topic, unsubscribe_lite_topic
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.workflow.workflow_graph import build_workflow
from supervisor_agent.utils.session.session_manger import session_manager

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
    session_id = request.get("session_id", "")
    main_trace_id = "main" + str(uuid.uuid4())

    # Register session with metadata
    session_manager.add_session(session_id, {
        "trace_id": main_trace_id,
        "user_input": user_input,
        "created_at": time.time()
    })
    logger.info(f"[Chat] Session registered: {session_id}, trace_id: {main_trace_id}")
    subscribe_lite_topic(session_id)
    # Initialize workflow state
    initial_state = {
        "trace_id": main_trace_id,
        "session_id": session_id,
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

    # Register response queue for streaming
    response_queue = stream_queue_manager.register_trace(main_trace_id)

    async def event_generator():
        """Generate SSE events for real-time streaming"""
        yield {"data": json.dumps({"type": "start", "trace_id": main_trace_id})}

        try:
            active_traces = set()
            completed_traces = set()
            is_chat_mode = False

            async def run_graph():
                """Execute LangGraph workflow asynchronously"""
                nonlocal is_chat_mode
                try:
                    async for event in app_graph.astream(initial_state):
                        for node_name, output in event.items():
                            logger.info(f"Graph node executed: {node_name}, output: {output}")

                            # Detect chat mode
                            if node_name == "chat_node":
                                is_chat_mode = True
                                logger.info("Detected chat mode, will wait for streaming completion")

                            # Register weather sub-trace for streaming
                            if "weather_trace_id" in output and output["weather_trace_id"]:
                                weather_tid = output["weather_trace_id"]
                                active_traces.add(weather_tid)
                                stream_queue_manager.register_sub_trace(weather_tid, main_trace_id)
                                logger.info(f"Registered weather trace: {weather_tid} -> {main_trace_id}")

                                # Save to session metadata for reconnection
                                metadata = session_manager.get_session_metadata(session_id)
                                if metadata:
                                    metadata["weather_trace_id"] = weather_tid
                                    session_manager.add_session(session_id, metadata)
                                    logger.info(f"[Session] Saved weather_trace_id to metadata: {weather_tid}")

                            # Register travel sub-trace for streaming
                            if "travel_trace_id" in output and output["travel_trace_id"]:
                                travel_tid = output["travel_trace_id"]
                                active_traces.add(travel_tid)
                                stream_queue_manager.register_sub_trace(travel_tid, main_trace_id)
                                logger.info(f"Registered travel trace: {travel_tid} -> {main_trace_id}")

                                # Save to session metadata for reconnection
                                metadata = session_manager.get_session_metadata(session_id)
                                if metadata:
                                    metadata["travel_trace_id"] = travel_tid
                                    session_manager.add_session(session_id, metadata)
                                    logger.info(f"[Session] Saved travel_trace_id to metadata: {travel_tid}")

                except Exception as e:
                    logger.error(f"Graph execution error: {e}", exc_info=True)

            # Start graph execution in background
            graph_task = asyncio.create_task(run_graph())

            stream_start_time = time.time()
            max_timeout = 120.0
            chat_stream_completed = False

            # Stream responses to client
            while True:
                # Check for direct chat response
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
                    # Calculate remaining timeout
                    remaining_timeout = max_timeout - (time.time() - stream_start_time)
                    if remaining_timeout <= 0:
                        logger.warning(f"Overall timeout reached for trace_id: {main_trace_id}")
                        yield {"data": json.dumps({"type": "error", "content": "响应超时"})}
                        break

                    # Wait for message payload from queue
                    payload = await asyncio.wait_for(response_queue.get(), timeout=min(remaining_timeout, 5.0))

                    # Extract metadata
                    metadata = payload.metadata or {}
                    is_final = metadata.get("is_final", False)
                    is_error = metadata.get("error", False)
                    chunk_index = metadata.get("chunk_index", 0)

                    sub_trace_id = payload.trace_id
                    role = payload.role.value if hasattr(payload.role, 'value') else str(payload.role)

                    # Handle error messages
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

                    # Stream content chunks
                    if payload.content:
                        yield {"data": json.dumps({
                            "type": "chunk",
                            "role": role,
                            "content": payload.content,
                            "chunk_index": chunk_index,
                            "is_final": is_final,
                            "sub_trace_id": sub_trace_id
                        })}

                    # Check if sub-trace or main trace is complete
                    if is_final:
                        if sub_trace_id != main_trace_id:
                            # Sub-trace completed
                            completed_traces.add(sub_trace_id)
                            logger.info(
                                f"Sub-trace completed: {sub_trace_id}, total completed: {len(completed_traces)}/{len(active_traces)}")

                            # Check if all sub-traces are done
                            if active_traces and completed_traces >= active_traces:
                                logger.info(f"All sub-traces completed for main trace: {main_trace_id}")
                                break
                        else:
                            # Main chat stream completed
                            logger.info(f"Chat streaming completed for main trace: {main_trace_id}")
                            chat_stream_completed = True
                            break

                except asyncio.TimeoutError:
                    # Retry checking for chat response on timeout
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

                    # Exit conditions
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

            # Wait for graph task to finish
            try:
                await asyncio.wait_for(graph_task, timeout=10.0)
            except asyncio.TimeoutError:
                logger.warning("Graph task timeout, continuing...")

        except Exception as e:
            logger.error(f"Event generator error: {e}", exc_info=True)
            yield {"data": json.dumps({"type": "error", "content": str(e)})}
        finally:
            # Clean up response queue (keep session for potential reconnection)
            stream_queue_manager.unregister_trace(main_trace_id, response_queue)
            yield {"data": "[DONE]"}

    return EventSourceResponse(event_generator())


@router.post("/disconnect")
async def disconnect(request: dict):
    """Pause session: unsubscribe from RocketMQ but keep session for potential reconnection"""
    session_id = request.get("session_id", "")
    logger.info(f"[Disconnect] Session ID: {session_id}")

    # Unsubscribe from RocketMQ topic (pause message delivery)
    try:
        unsubscribe_lite_topic(session_id)
        logger.info(f"[Disconnect] Unsubscribed from session: {session_id}")
    except Exception as e:
        logger.error(f"[Disconnect] Failed to unsubscribe: {e}", exc_info=True)

    # Note: Keep session in session_manager for potential reconnection
    # Session will be auto-cleaned by timeout or explicit cleanup endpoint

    # Update session status to inactive
    metadata = session_manager.get_session_metadata(session_id)
    if metadata:
        metadata["status"] = "disconnected"
        metadata["disconnected_at"] = time.time()
        session_manager.add_session(session_id, metadata)  # Update metadata

    return JSONResponse(content={
        "status": "success",
        "message": "Disconnected. Session kept for reconnection.",
        "session_id": session_id
    })

@router.post("/end-session")
async def end_session(request: dict):
    """Completely end session: remove from session_manager and unsubscribe"""
    session_id = request.get("session_id", "")
    logger.info(f"[End Session] Session ID: {session_id}")

    # Remove session completely
    removed = session_manager.remove_session(session_id)

    # Unsubscribe from RocketMQ
    if removed:
        try:
            unsubscribe_lite_topic(session_id)
            logger.info(f"[End Session] Unsubscribed and removed: {session_id}")
        except Exception as e:
            logger.error(f"[End Session] Failed to unsubscribe: {e}", exc_info=True)

    return JSONResponse(content={
        "status": "success",
        "message": "Session ended and cleaned up",
        "session_id": session_id,
        "removed": removed
    })


@router.post("/reconnect")
async def reconnect(request: dict):
    """Reconnect SSE stream and resume message delivery from RocketMQ"""
    session_id = request.get("session_id", "")
    logger.info(f"[Reconnect] Session ID: {session_id}")

    # Re-register session (update last_active timestamp)
    session_manager.add_session(session_id)

    # Get main_trace_id from session metadata
    metadata = session_manager.get_session_metadata(session_id)
    main_trace_id = metadata.get("trace_id") if metadata else None

    if not main_trace_id:
        logger.warning(f"[Reconnect] No active trace found for session: {session_id}")
        return JSONResponse(content={
            "status": "error",
            "message": "No active session found. Please start a new chat.",
            "session_id": session_id
        }, status_code=404)

    # ✅ Step 1: Register response queue FIRST (before subscribing)
    response_queue = stream_queue_manager.register_trace(main_trace_id)
    logger.info(f"[Reconnect] Registered response queue for trace_id: {main_trace_id}")

    # ✅ Step 1.5: Re-register any known sub-traces from session metadata
    weather_trace_id = metadata.get("weather_trace_id")
    travel_trace_id = metadata.get("travel_trace_id")

    if weather_trace_id:
        stream_queue_manager.register_sub_trace(weather_trace_id, main_trace_id)
        logger.info(f"[Reconnect] Re-registered weather sub-trace: {weather_trace_id} -> {main_trace_id}")

    if travel_trace_id:
        stream_queue_manager.register_sub_trace(travel_trace_id, main_trace_id)
        logger.info(f"[Reconnect] Re-registered travel sub-trace: {travel_trace_id} -> {main_trace_id}")

    async def event_generator():
        """Resume SSE streaming for reconnected client"""

        # ✅ Step 2: Send reconnection confirmation to frontend
        yield {"data": json.dumps({
            "type": "reconnected",
            "session_id": session_id,
            "trace_id": main_trace_id
        })}

        # ✅ Step 3: NOW subscribe to RocketMQ (queue is ready)
        try:
            subscribe_lite_topic(session_id)
            logger.info(f"[Reconnect] Re-subscribed to session: {session_id}")
        except Exception as e:
            logger.error(f"[Reconnect] Subscribe failed: {e}", exc_info=True)
            yield {"data": json.dumps({
                "type": "error",
                "content": f"Failed to subscribe: {str(e)}"
            })}
            yield {"data": "[DONE]"}
            return

        try:
            max_timeout = 120.0
            start_time = time.time()

            while time.time() - start_time < max_timeout:
                try:
                    # Wait for messages from RocketMQ consumer
                    payload = await asyncio.wait_for(response_queue.get(), timeout=5.0)

                    msg_metadata = payload.metadata or {}
                    is_final = msg_metadata.get("is_final", False)
                    is_error = msg_metadata.get("error", False)
                    chunk_index = msg_metadata.get("chunk_index", 0)

                    role = payload.role.value if hasattr(payload.role, 'value') else str(payload.role)

                    # Send payload to frontend
                    yield {"data": json.dumps({
                        "type": "error" if is_error else "chunk",
                        "role": role,
                        "content": payload.content,
                        "chunk_index": chunk_index,
                        "is_final": is_final,
                        "sub_trace_id": payload.trace_id
                    })}

                    # Stop if final message received
                    if is_final:
                        logger.info(f"[Reconnect] Final message received for trace: {main_trace_id}")
                        break

                except asyncio.TimeoutError:
                    # Continue waiting for messages
                    continue

        except Exception as e:
            logger.error(f"[Reconnect] Stream error: {e}", exc_info=True)
            yield {"data": json.dumps({"type": "error", "content": str(e)})}
        finally:
            logger.info(f"[Reconnect] receive response queue for trace_id: {main_trace_id}")
            yield {"data": "[DONE]"}

    return EventSourceResponse(event_generator())


@router.get("/sessions")
async def get_active_sessions():
    """Get all active sessions for debugging/monitoring"""
    active_sessions = session_manager.get_active_sessions()
    session_details = []

    # Build session details with metadata
    for session_id in active_sessions:
        metadata = session_manager.get_session_metadata(session_id)
        session_details.append({
            "session_id": session_id,
            "metadata": metadata
        })

    return JSONResponse(content={
        "status": "success",
        "count": len(active_sessions),
        "sessions": session_details
    })
