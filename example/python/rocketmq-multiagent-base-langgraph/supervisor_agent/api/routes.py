"""FastAPI routes for Supervisor Agent"""
import json
import uuid
import time
import asyncio
from pathlib import Path
from typing import AsyncGenerator, Dict, Any

from fastapi import APIRouter
from fastapi.responses import HTMLResponse, JSONResponse
from sse_starlette.sse import EventSourceResponse

from common.rocketmq.rocketmq_utils import logger
from supervisor_agent.utils.constants.constants import (
    SESSION_KEY_TRACE_ID,
    SESSION_KEY_USER_INPUT,
    SESSION_KEY_CREATED_AT,
    SESSION_KEY_STATUS,
    SESSION_KEY_DISCONNECTED_AT,
    SESSION_KEY_WEATHER_TRACE_ID,
    SESSION_KEY_TRAVEL_TRACE_ID,
    SESSION_KEY_INTENT,
    SESSION_STATUS_ACTIVE,
    SESSION_STATUS_DISCONNECTED,
    SESSION_STATUS_COMPLETED,
    STATE_KEY_WEATHER_TRACE_ID,
    STATE_KEY_TRAVEL_TRACE_ID,
    MSG_METADATA_IS_FINAL,
    MSG_METADATA_ERROR,
    MSG_METADATA_CHUNK_INDEX,
    SSE_EVENT_TYPE_START,
    SSE_EVENT_TYPE_CHUNK,
    SSE_EVENT_TYPE_ERROR,
    SSE_EVENT_TYPE_RECONNECTED,
    SSE_EVENT_DONE,
    TRACE_PREFIX_MAIN,
    NODE_CHAT, STATE_KEY_TRACE_ID, STATE_KEY_SESSION_ID, STATE_KEY_USER_INPUT, STATE_KEY_INTENT, STATE_KEY_CITY,
    STATE_KEY_DATE_INFO, STATE_KEY_WEATHER_DATA, STATE_KEY_FINAL_RESPONSE, STATE_KEY_WEATHER_COMPLETE
)
from supervisor_agent.utils.rocketmq.mq_service import subscribe_lite_topic, unsubscribe_lite_topic
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.workflow.workflow_graph import build_workflow
from supervisor_agent.utils.session.session_manager import session_manager

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


# ==================== Core Business Logic ====================

async def execute_langgraph_workflow(session_id: str, user_input: str, main_trace_id: str) -> Dict[str, Any]:
    """
    Execute LangGraph workflow and register sub-traces.

    Args:
        session_id: Session identifier
        user_input: User's input message
        main_trace_id: Main trace ID for this conversation

    Returns:
        Workflow execution result metadata
    """
    initial_state = {
        STATE_KEY_TRACE_ID: main_trace_id,
        STATE_KEY_SESSION_ID: session_id,
        STATE_KEY_USER_INPUT: user_input,
        STATE_KEY_INTENT: "",
        STATE_KEY_CITY: "",
        STATE_KEY_DATE_INFO: "",
        STATE_KEY_WEATHER_DATA: "",
        STATE_KEY_FINAL_RESPONSE: "",
        STATE_KEY_WEATHER_TRACE_ID: None,
        STATE_KEY_TRAVEL_TRACE_ID: None,
        STATE_KEY_WEATHER_COMPLETE: False
    }

    active_traces = set()
    is_chat_mode = False

    try:
        async for event in app_graph.astream(initial_state):
            for node_name, output in event.items():
                logger.info(f"Graph node executed: {node_name}, output: {output}")

                # Detect chat mode
                if node_name == NODE_CHAT:
                    is_chat_mode = True
                    logger.info("Detected chat mode, will wait for streaming completion")

                # Register weather sub-trace for streaming
                if STATE_KEY_WEATHER_TRACE_ID in output and output[STATE_KEY_WEATHER_TRACE_ID]:
                    weather_tid = output[STATE_KEY_WEATHER_TRACE_ID]
                    active_traces.add(weather_tid)
                    stream_queue_manager.register_sub_trace(weather_tid, main_trace_id)
                    logger.info(f"Registered weather trace: {weather_tid} -> {main_trace_id}")

                    # Save to session metadata for reconnection
                    metadata = session_manager.get_session_metadata(session_id)
                    if metadata:
                        metadata[SESSION_KEY_WEATHER_TRACE_ID] = weather_tid
                        session_manager.add_session(session_id, metadata)
                        logger.info(f"[Session] Saved weather_trace_id to metadata: {weather_tid}")

                # Register travel sub-trace for streaming
                if STATE_KEY_TRAVEL_TRACE_ID in output and output[STATE_KEY_TRAVEL_TRACE_ID]:
                    travel_tid = output[STATE_KEY_TRAVEL_TRACE_ID]
                    active_traces.add(travel_tid)
                    stream_queue_manager.register_sub_trace(travel_tid, main_trace_id)
                    logger.info(f"Registered travel trace: {travel_tid} -> {main_trace_id}")

                    # Save to session metadata for reconnection
                    metadata = session_manager.get_session_metadata(session_id)
                    if metadata:
                        metadata[SESSION_KEY_TRAVEL_TRACE_ID] = travel_tid
                        session_manager.add_session(session_id, metadata)
                        logger.info(f"[Session] Saved travel_trace_id to metadata: {travel_tid}")

    except Exception as e:
        logger.error(f"Graph execution error: {e}", exc_info=True)
        raise

    return {
        "active_traces": active_traces,
        "is_chat_mode": is_chat_mode
    }


async def stream_messages_to_client(
        main_trace_id: str,
        response_queue: asyncio.Queue,
        max_timeout: float = 120.0
) -> AsyncGenerator[Dict[str, Any], None]:
    """
    Stream messages from queue to client via SSE.

    Args:
        main_trace_id: Main trace ID
        response_queue: Queue containing message payloads
        max_timeout: Maximum timeout in seconds

    Yields:
        SSE event dictionaries
    """
    active_traces = set()
    completed_traces = set()
    chat_stream_completed = False
    stream_start_time = time.time()

    while True:
        # Check for direct chat response
        chat_response = stream_queue_manager.get_chat_response(main_trace_id)
        if chat_response:
            logger.info(f"Sending chat response: {chat_response}")
            yield {"data": json.dumps({
                "type": SSE_EVENT_TYPE_CHUNK,
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
                yield {"data": json.dumps({"type": SSE_EVENT_TYPE_ERROR, "content": "响应超时"})}
                break

            # Wait for message payload from queue
            payload = await asyncio.wait_for(response_queue.get(), timeout=min(remaining_timeout, 5.0))

            # Extract metadata
            msg_metadata = payload.metadata or {}
            is_final = msg_metadata.get(MSG_METADATA_IS_FINAL, False)
            is_error = msg_metadata.get(MSG_METADATA_ERROR, False)
            chunk_index = msg_metadata.get(MSG_METADATA_CHUNK_INDEX, 0)

            sub_trace_id = payload.trace_id
            role = payload.role.value if hasattr(payload.role, 'value') else str(payload.role)

            # Handle error messages
            if is_error:
                yield {"data": json.dumps({
                    "type": SSE_EVENT_TYPE_ERROR,
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
                    "type": SSE_EVENT_TYPE_CHUNK,
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
                    "type": SSE_EVENT_TYPE_CHUNK,
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
            elif not active_traces:
                continue
            elif active_traces and completed_traces >= active_traces:
                break
            elif time.time() - stream_start_time > max_timeout:
                logger.warning(f"Timeout waiting for messages for trace_id: {main_trace_id}")
                yield {"data": json.dumps({"type": SSE_EVENT_TYPE_ERROR, "content": "响应超时"})}
                break


async def stream_reconnected_messages(
        main_trace_id: str,
        response_queue: asyncio.Queue,
        intent: str,
        max_timeout: float = 120.0
) -> AsyncGenerator[Dict[str, Any], None]:
    """
    Stream messages for reconnected client.

    Args:
        main_trace_id: Main trace ID
        response_queue: Queue containing message payloads
        intent: Intent type for determining final trace
        max_timeout: Maximum timeout in seconds

    Yields:
        SSE event dictionaries
    """
    start_time = time.time()

    while time.time() - start_time < max_timeout:
        try:
            # Wait for messages from RocketMQ consumer
            payload = await asyncio.wait_for(response_queue.get(), timeout=5.0)

            msg_metadata = payload.metadata or {}
            is_final = msg_metadata.get(MSG_METADATA_IS_FINAL, False)
            is_error = msg_metadata.get(MSG_METADATA_ERROR, False)
            chunk_index = msg_metadata.get(MSG_METADATA_CHUNK_INDEX, 0)
            trace_id = payload.trace_id

            # Check if this is the final trace based on intent
            is_last_trace = intent and trace_id.startswith(intent)

            role = payload.role.value if hasattr(payload.role, 'value') else str(payload.role)

            # Send payload to frontend
            yield {"data": json.dumps({
                "type": SSE_EVENT_TYPE_ERROR if is_error else SSE_EVENT_TYPE_CHUNK,
                "role": role,
                "content": payload.content,
                "chunk_index": chunk_index,
                "is_final": is_final,
                "sub_trace_id": trace_id
            })}

            # Stop if final message received for the last trace
            if is_final and is_last_trace:
                logger.info(f"[Reconnect] Final message received for trace: {main_trace_id}")
                break

        except asyncio.TimeoutError:
            # Continue waiting for messages
            continue


# ==================== SSE Event Generators ====================

async def create_chat_event_generator(session_id: str, user_input: str, main_trace_id: str) -> AsyncGenerator[Dict[str, Any], None]:
    """Create event generator for new chat sessions"""
    yield {"data": json.dumps({"type": SSE_EVENT_TYPE_START, "trace_id": main_trace_id})}

    # Register response queue
    response_queue = stream_queue_manager.register_trace(main_trace_id)

    try:
        # Start LangGraph workflow in background
        graph_task = asyncio.create_task(
            execute_langgraph_workflow(session_id, user_input, main_trace_id)
        )

        # Stream messages to client
        async for event in stream_messages_to_client(main_trace_id, response_queue):
            yield event

        # Wait for graph task to finish
        try:
            await asyncio.wait_for(graph_task, timeout=10.0)
        except asyncio.TimeoutError:
            logger.warning("Graph task timeout, continuing...")

    except Exception as e:
        logger.error(f"Event generator error: {e}", exc_info=True)
        yield {"data": json.dumps({"type": SSE_EVENT_TYPE_ERROR, "content": str(e)})}
    finally:
        # Clean up response queue
        # stream_queue_manager.unregister_trace(main_trace_id, response_queue)
        yield {"data": SSE_EVENT_DONE}


async def create_reconnect_event_generator(session_id: str, main_trace_id: str, intent: str) -> AsyncGenerator[Dict[str, Any], None]:
    """Create event generator for reconnected sessions"""
    # Send reconnection confirmation
    yield {"data": json.dumps({
        "type": SSE_EVENT_TYPE_RECONNECTED,
        "session_id": session_id,
        "trace_id": main_trace_id
    })}

    # Register response queue
    response_queue = stream_queue_manager.register_trace(main_trace_id)

    try:
        # Subscribe to RocketMQ
        subscribe_lite_topic(session_id)
        logger.info(f"[Reconnect] Re-subscribed to session: {session_id}")

        # Stream messages to client
        async for event in stream_reconnected_messages(main_trace_id, response_queue, intent):
            yield event

    except Exception as e:
        logger.error(f"[Reconnect] Stream error: {e}", exc_info=True)
        yield {"data": json.dumps({"type": SSE_EVENT_TYPE_ERROR, "content": str(e)})}
    finally:
        logger.info(f"[Reconnect] Cleanup response queue for trace_id: {main_trace_id}")
        stream_queue_manager.unregister_trace(main_trace_id, response_queue)
        yield {"data": SSE_EVENT_DONE}


# ==================== API Endpoints ====================

@router.post("/chat")
async def chat(request: dict):
    """Chat endpoint with SSE streaming support"""
    user_input = request.get("message")
    session_id = request.get("session_id", "")
    main_trace_id = TRACE_PREFIX_MAIN + str(uuid.uuid4())

    # Register session with metadata
    session_manager.add_session(session_id, {
        SESSION_KEY_TRACE_ID: main_trace_id,
        SESSION_KEY_USER_INPUT: user_input,
        SESSION_KEY_CREATED_AT: time.time(),
        SESSION_KEY_STATUS: SESSION_STATUS_ACTIVE
    })
    logger.info(f"[Chat] Session registered: {session_id}, trace_id: {main_trace_id}")

    # Subscribe to RocketMQ topic
    subscribe_lite_topic(session_id)

    return EventSourceResponse(
        create_chat_event_generator(session_id, user_input, main_trace_id)
    )


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

    # Update session status to inactive
    metadata = session_manager.get_session_metadata(session_id)
    if metadata:
        metadata[SESSION_KEY_STATUS] = SESSION_STATUS_DISCONNECTED
        metadata[SESSION_KEY_DISCONNECTED_AT] = time.time()
        session_manager.add_session(session_id, metadata)

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
    main_trace_id = metadata.get(SESSION_KEY_TRACE_ID) if metadata else None

    if not main_trace_id:
        logger.warning(f"[Reconnect] No active trace found for session: {session_id}")
        return JSONResponse(content={
            "status": "error",
            "message": "No active session found. Please start a new chat.",
            "session_id": session_id
        }, status_code=404)

    # Get intent and sub-trace IDs from metadata
    intent = metadata.get(SESSION_KEY_INTENT, "")
    weather_trace_id = metadata.get(SESSION_KEY_WEATHER_TRACE_ID)
    travel_trace_id = metadata.get(SESSION_KEY_TRAVEL_TRACE_ID)

    # Check if session has already completed
    status = metadata.get(SESSION_KEY_STATUS, SESSION_STATUS_ACTIVE)
    if status == SESSION_STATUS_COMPLETED:
        logger.info(f"[Reconnect] Session already completed: {session_id}")

        async def completed_event_generator():
            """Send immediate completion for already-finished session"""
            yield {"data": json.dumps({
                "type": SSE_EVENT_TYPE_RECONNECTED,
                "session_id": session_id,
                "trace_id": main_trace_id
            })}
            yield {"data": SSE_EVENT_DONE}

        return EventSourceResponse(completed_event_generator())

    # Register response queue FIRST (before subscribing)
    response_queue = stream_queue_manager.register_trace(main_trace_id)
    logger.info(f"[Reconnect] Registered response queue for trace_id: {main_trace_id}")

    # Re-register any known sub-traces from session metadata
    if weather_trace_id:
        stream_queue_manager.register_sub_trace(weather_trace_id, main_trace_id)
        logger.info(f"[Reconnect] Re-registered weather sub-trace: {weather_trace_id} -> {main_trace_id}")

    if travel_trace_id:
        stream_queue_manager.register_sub_trace(travel_trace_id, main_trace_id)
        logger.info(f"[Reconnect] Re-registered travel sub-trace: {travel_trace_id} -> {main_trace_id}")

    return EventSourceResponse(
        create_reconnect_event_generator(session_id, main_trace_id, intent)
    )
