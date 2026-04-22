"""LangGraph workflow nodes for Supervisor Agent"""
import json
import re
import threading
import time
import asyncio
from datetime import datetime
from typing import Literal
from langchain_community.chat_models import ChatTongyi
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END

from common.models import MessagePayload, AgentRole
from common.rocketmq_utils import logger
from supervisor_agent.utils.config.config import (
    DASHSCOPE_API_KEY,
    WEATHER_AGENT_TOPIC,
    TRAVEL_AGENT_TOPIC,
    SESSION_ID,
    WORK_AGENT_RESPONSE_TOPIC
)
from supervisor_agent.utils.models.models import AgentState
from supervisor_agent.utils.session.session_manger import session_manager
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.rocketmq.mq_service import send_message

# Initialize Qwen LLM for intent recognition and chat
llm_supervisor = ChatTongyi(
    model="qwen-turbo",
    dashscope_api_key=DASHSCOPE_API_KEY,
    temperature=0.1
)

# Shared storage for aggregating streaming results from Worker Agents
result_store = {}
lock = threading.Lock()


def wait_for_result_sync(trace_id: str, timeout: int):
    """Blocking wait for next payload chunk from result_store by trace_id"""
    start = time.time()
    while time.time() - start < timeout:
        with lock:
            if trace_id in result_store and result_store[trace_id]:
                return result_store[trace_id].pop(0)  # FIFO: remove and return first element
        time.sleep(0.1)
    return None


def router_node(state: AgentState):
    """Router node: Intent recognition and entity extraction using Qwen LLM"""
    current_time_str = datetime.now().strftime("%Y年%m月%d日 %A")

    system_prompt = f"""
    当前系统时间: {current_time_str}
    
    你是一个智能路由主管。分析用户输入，提取关键信息，返回严格的 JSON 格式：
    1. 查天气: {{"intent": "weather", "city": "城市名", "date": "具体日期描述"}}
    2. 规划行程: {{"intent": "travel", "city": "城市名", "date": "具体日期描述"}}
    3. 闲聊: {{"intent": "chat"}}
    
    规则：
    - 如果用户未提及日期，weather 默认填"今天"，travel 默认填"近期"。
    - 只返回 JSON，不要包含 Markdown 标记或其他文字。
    """

    user_prompt = f"用户输入: {state['user_input']}"

    try:
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_prompt)
        ]
        response = llm_supervisor.invoke(messages)
        raw_content = response.content
        cleaned_content = re.sub(r'^```json\s*|\s*```$', '', raw_content.strip(), flags=re.MULTILINE)

        if not cleaned_content.startswith('{'):
            start_idx = cleaned_content.find('{')
            end_idx = cleaned_content.rfind('}')
            if start_idx != -1 and end_idx != -1:
                cleaned_content = cleaned_content[start_idx : end_idx + 1]

        data = json.loads(cleaned_content)

        return {
            "intent": data.get("intent", "chat"),
            "city": data.get("city", ""),
            "date_info": data.get("date", "今天")
        }
    except Exception as e:
        print(f"Router Error: {e}")
        return {"intent": "chat"}


def weather_node(state: AgentState):
    """Weather node: Send query to Weather Agent and synchronously collect results"""
    city = state.get("city", "")
    date_info = state.get("date_info", "今天")
    intent = state.get("intent", "")
    main_trace_id = state.get("trace_id", "")
    session_id = state.get("session_id", SESSION_ID)

    if not city:
        return {"weather_data": "未识别到城市", "final_response": "请提供城市名称", "weather_complete": True}

    print(f"[Web] Sending Weather Task: {city} @ {date_info}")

    content_json = json.dumps({"city": city, "date": date_info})
    weather_trace_id = "weather_" + main_trace_id

    # Register sub-trace mapping for routing messages to main trace's SSE stream
    stream_queue_manager.register_sub_trace(weather_trace_id, main_trace_id)
    logger.info(f"Registered weather sub-trace: {weather_trace_id} -> {main_trace_id}")

    metadata = session_manager.get_session_metadata(session_id)
    if metadata:
        metadata["weather_trace_id"] = weather_trace_id
        metadata["intent"] = intent
        session_manager.add_session(session_id, metadata)

    # Send weather query to Weather Agent via RocketMQ
    # todo
    send_message(WEATHER_AGENT_TOPIC, MessagePayload(
        trace_id=weather_trace_id,
        role=AgentRole.WEATHER,
        content=content_json,
        bind_topic=WORK_AGENT_RESPONSE_TOPIC,
        lite_topic=session_id
    ))

    # Synchronously collect streaming weather chunks (blocking operation)
    weather_chunks = []
    start_time = time.time()
    timeout = 300.0

    print(f"[Web] Waiting for weather data collection...")

    while time.time() - start_time < timeout:
        payload = wait_for_result_sync(weather_trace_id, timeout=1)
        if payload:
            weather_chunks.append(payload.content)
            # Stop when receiving final chunk marker
            if payload.metadata and payload.metadata.get("is_final", False):
                logger.info(
                    f"Weather collection complete for {weather_trace_id}: {len(''.join(weather_chunks))} chars")
                break
        time.sleep(0.1)

    complete_weather = "".join(weather_chunks)

    if not complete_weather:
        logger.warning(f"[Web] Weather data timeout")
        complete_weather = "天气信息获取超时,请基于一般情况规划行程"

    # Cache complete weather data for travel_node fallback retrieval
    with lock:
        result_store[f"{weather_trace_id}_complete"] = complete_weather
        logger.info(f"[Aggregation] Weather data stored for {weather_trace_id}")

    return {
        "weather_trace_id": weather_trace_id,
        "intent": intent,
        "weather_data": complete_weather,
        "weather_complete": True
    }


def travel_node(state: AgentState):
    """Travel node: Send planning request to Travel Agent with weather context"""
    weather_trace_id = state.get("weather_trace_id", "")
    weather_data = state.get("weather_data", "")
    session_id = state.get("session_id", SESSION_ID)
    main_trace_id = state.get("trace_id", "")

# Fallback: retrieve weather data from result_store if not in state
    if not weather_data:
        logger.warning(f"[Web] Weather data not found in state, trying to retrieve from store")
        complete_key = f"{weather_trace_id}_complete"
        start_time = time.time()
        timeout = 300.0

        while time.time() - start_time < timeout:
            with lock:
                if complete_key in result_store:
                    weather_data = result_store[complete_key]
                    break
            time.sleep(0.1)

        if not weather_data:
            logger.warning(f"[Web] Weather data still not available, using default")
            weather_data = "天气信息获取超时,请基于一般情况规划行程"

    travel_trace_id = "travel_" + main_trace_id
    date_info = state.get("date_info", "近期")
    user_input = state["user_input"]

    metadata = session_manager.get_session_metadata(session_id)
    if metadata:
        metadata["travel_trace_id"] = travel_trace_id
        session_manager.add_session(session_id, metadata)
        logger.info(f"[Session] Saved travel_trace_id to metadata: {travel_trace_id}")
    print(f"[Web] Sending Travel Task with weather info ({len(weather_data)} chars)")

    content_json = json.dumps({
        "request": user_input,
        "date": date_info,
        "weather_info": weather_data,
    })

    # Send travel planning request to Travel Agent via RocketMQ
    send_message(TRAVEL_AGENT_TOPIC, MessagePayload(
        trace_id=travel_trace_id,
        role=AgentRole.TRAVEL,
        content=content_json,
        bind_topic=WORK_AGENT_RESPONSE_TOPIC,
        lite_topic=session_id
    ))

    return {"travel_trace_id": travel_trace_id}


def chat_node(state: AgentState):
    """Chat node: Direct LLM conversation with real-time streaming to frontend"""
    trace_id = state["trace_id"]
    user_input = state["user_input"]

    system_prompt = """你是一个友好助手，可以进行日常闲聊。请用自然、友好的语气与用户交流。"""

    try:
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=user_input)
        ]

        chunk_index = 0
        full_response = []

        # Stream LLM response chunks
        for chunk in llm_supervisor.stream(messages):
            if chunk.content:
                content = chunk.content
                full_response.append(content)

                payload = MessagePayload(
                    trace_id=trace_id,
                    role=AgentRole.ASSISTANT,
                    content=content,
                    bind_topic="",
                    lite_topic="",
                    metadata={
                        "chunk_index": chunk_index,
                        "is_final": False,
                        "error": False
                    }
                )

                # Forward chunk to SSE stream queue
                try:
                    loop = asyncio.get_running_loop()
                    asyncio.create_task(stream_queue_manager.put_payload(payload))
                except RuntimeError:
                    if stream_queue_manager.loop:
                        def put_in_thread():
                            asyncio.run_coroutine_threadsafe(
                                stream_queue_manager.put_payload(payload),
                                stream_queue_manager.loop
                            )
                        threading.Thread(target=put_in_thread, daemon=True).start()

                chunk_index += 1

        complete_response = "".join(full_response)

        # Send final marker to indicate streaming completion
        final_payload = MessagePayload(
            trace_id=trace_id,
            role=AgentRole.ASSISTANT,
            content="",
            bind_topic="",
            lite_topic="",
            metadata={
                "chunk_index": chunk_index,
                "is_final": True,
                "error": False
            }
        )

        try:
            loop = asyncio.get_running_loop()
            asyncio.create_task(stream_queue_manager.put_payload(final_payload))
        except RuntimeError:
            if stream_queue_manager.loop:
                def put_final_in_thread():
                    asyncio.run_coroutine_threadsafe(
                        stream_queue_manager.put_payload(final_payload),
                        stream_queue_manager.loop
                    )
                threading.Thread(target=put_final_in_thread, daemon=True).start()

        logger.info(f"Chat response completed for trace_id: {trace_id}, length: {len(complete_response)}")

        return {"final_response": complete_response}

    except Exception as e:
        logger.error(f"Chat node error: {e}", exc_info=True)

        # Send error payload to frontend
        error_payload = MessagePayload(
            trace_id=trace_id,
            role=AgentRole.ASSISTANT,
            content=f"抱歉，处理您的请求时出现错误：{str(e)}",
            bind_topic="",
            lite_topic="",
            metadata={
                "chunk_index": 0,
                "is_final": True,
                "error": True
            }
        )

        try:
            loop = asyncio.get_running_loop()
            asyncio.create_task(stream_queue_manager.put_payload(error_payload))
        except RuntimeError:
            if stream_queue_manager.loop:
                def put_error_in_thread():
                    asyncio.run_coroutine_threadsafe(
                        stream_queue_manager.put_payload(error_payload),
                        stream_queue_manager.loop
                    )
                threading.Thread(target=put_error_in_thread, daemon=True).start()

        return {"final_response": f"Error: {str(e)}"}


def route_after_router(state: AgentState) -> Literal["weather_node", "travel_node", "chat_node"]:
    """Routing logic after router node: determine next node based on intent"""
    intent = state.get("intent")
    if intent == "weather":
        return "weather_node"
    elif intent == "travel":
        return "weather_node"  # Travel also starts with weather check
    else:
        return "chat_node"


def route_after_weather(state: AgentState) -> Literal["travel_node", END]:
    """Routing logic after weather node: proceed to travel or end based on intent"""
    intent = state.get("intent")
    if intent == "travel":
        return "travel_node"  # Continue to travel planning
    else:
        return END  # End workflow for weather-only queries
