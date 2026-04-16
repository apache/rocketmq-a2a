"""LangGraph workflow nodes for Supervisor Agent"""
import json
import uuid
import threading
import time
import asyncio
from datetime import datetime
from typing import Literal
from langchain_community.chat_models import ChatTongyi
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.graph import END

from common.models import MessagePayload, AgentRole
from common.mq_toos import logger
from supervisor_agent_optimize.my_common.config.config import (
    DASHSCOPE_API_KEY,
    WEATHER_AGENT_TOPIC,
    TRAVEL_AGENT_TOPIC,
    SESSION_ID,
    WORK_AGENT_RESPONSE_TOPIC
)
from supervisor_agent_optimize.my_common.models.models import AgentState
from supervisor_agent_optimize.my_common.stream.stream_manager import stream_queue_manager
from supervisor_agent_optimize.rocketmq.mq_service import send_message_new

# Initialize LLM
llm_supervisor = ChatTongyi(
    model="qwen-turbo",
    dashscope_api_key=DASHSCOPE_API_KEY,
    temperature=0.1
)

# Shared state for result aggregation
result_store = {}
lock = threading.Lock()


def wait_for_result_sync(trace_id: str, timeout: int):
    """Wait for next payload chunk for given trace_id"""
    start = time.time()
    while time.time() - start < timeout:
        with lock:
            if trace_id in result_store and result_store[trace_id]:
                return result_store[trace_id].pop(0)
        time.sleep(0.1)
    return None


def router_node(state: AgentState):
    """Router node: Intent recognition and entity extraction using Qwen"""
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

        clean = response.content.replace("", "").strip()
        data = json.loads(clean)

        return {
            "intent": data.get("intent", "chat"),
            "city": data.get("city", ""),
            "date_info": data.get("date", "今天")
        }
    except Exception as e:
        print(f"Router Error: {e}")
        return {"intent": "chat"}


def weather_node(state: AgentState):
    """Weather node: Send task to Weather Agent and collect results"""
    city = state.get("city", "")
    date_info = state.get("date_info", "今天")
    intent = state.get("intent", "")

    if not city:
        return {"weather_data": "未识别到城市", "final_response": "请提供城市名称", "weather_complete": True}

    print(f"[Web] Sending Weather Task: {city} @ {date_info}")

    content_json = json.dumps({"city": city, "date": date_info})
    weather_trace_id = str(uuid.uuid4())

    send_message_new(WEATHER_AGENT_TOPIC, MessagePayload(
        trace_id=weather_trace_id,
        role=AgentRole.WEATHER,
        content=content_json,
        bind_topic=WORK_AGENT_RESPONSE_TOPIC,
        lite_topic=SESSION_ID
    ))

    def collect_weather_result():
        """Background thread to collect complete weather data"""
        weather_chunks = []
        start_time = time.time()
        timeout = 30.0

        while time.time() - start_time < timeout:
            payload = wait_for_result_sync(weather_trace_id, timeout=1)
            if payload:
                weather_chunks.append(payload.content)
                if payload.metadata and payload.metadata.get("is_final", False):
                    logger.info(
                        f"Weather collection complete for {weather_trace_id}: {len(''.join(weather_chunks))} chars")
                    break
            time.sleep(0.1)

        complete_weather = "".join(weather_chunks)
        with lock:
            result_store[f"{weather_trace_id}_complete"] = complete_weather
            logger.info(f"[Aggregation] Weather data stored for {weather_trace_id}")

    collector_thread = threading.Thread(target=collect_weather_result, daemon=True)
    collector_thread.start()

    return {
        "weather_trace_id": weather_trace_id,
        "intent": intent,
        "weather_complete": False
    }


def travel_node(state: AgentState):
    """Travel node: Wait for weather data, then send task to Travel Agent"""
    weather_trace_id = state.get("weather_trace_id", "")

    complete_key = f"{weather_trace_id}_complete"
    weather_data = ""
    start_time = time.time()
    timeout = 35.0

    print(f"[Web] Waiting for weather data to complete before sending travel task...")

    while time.time() - start_time < timeout:
        with lock:
            if complete_key in result_store:
                weather_data = result_store[complete_key]
                logger.info(f"[Web] Weather data collected: {len(weather_data)} chars")
                break
        time.sleep(0.2)

    if not weather_data:
        logger.warning(f"[Web] Weather data timeout, proceeding without it")
        weather_data = "天气信息获取超时,请基于一般情况规划行程"

    travel_trace_id = str(uuid.uuid4())
    date_info = state.get("date_info", "近期")
    user_input = state["user_input"]

    print(f"[Web] Sending Travel Task with weather info ({len(weather_data)} chars)")

    content_json = json.dumps({
        "request": user_input,
        "date": date_info,
        "weather_info": weather_data,
    })

    send_message_new(TRAVEL_AGENT_TOPIC, MessagePayload(
        trace_id=travel_trace_id,
        role=AgentRole.TRAVEL,
        content=content_json,
        bind_topic=WORK_AGENT_RESPONSE_TOPIC,
        lite_topic=SESSION_ID
    ))

    return {"travel_trace_id": travel_trace_id}


def chat_node(state: AgentState):
    """Chat node: Direct LLM chat with streaming support"""
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
    """Routing logic after router node"""
    intent = state.get("intent")
    if intent == "weather":
        return "weather_node"
    elif intent == "travel":
        return "weather_node"
    else:
        return "chat_node"


def route_after_weather(state: AgentState) -> Literal["travel_node", END]:
    """Routing logic after weather node"""
    intent = state.get("intent")
    if intent == "travel":
        return "travel_node"
    else:
        return END
