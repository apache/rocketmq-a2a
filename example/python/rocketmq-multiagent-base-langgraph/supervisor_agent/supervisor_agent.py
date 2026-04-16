import asyncio
import logging
import os
import uuid
import json
import threading
import time
from asyncio import Queue
from collections import defaultdict
from contextlib import asynccontextmanager
from datetime import datetime
from typing import TypedDict, Literal, Optional
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import HTMLResponse
from rocketmq import MessageListener, ConsumeResult, Message
from sse_starlette.sse import EventSourceResponse
from dotenv import load_dotenv

# LangChain for Direct LLM Call (No App ID needed for Supervisor)
from langchain_community.chat_models import ChatTongyi
from langchain_core.messages import HumanMessage, SystemMessage

from langgraph.graph import StateGraph, END

from common.mq_toos import build_producer, build_message, build_lite_push_consumer, logger
from common.models import MessagePayload, AgentRole

load_dotenv()

WEATHER_AGENT_TOPIC = "WeatherAgentTask"
TRAVEL_AGENT_TOPIC = "TravelAgentTask"

ENDPOINT = os.getenv("ROCKETMQ_ENDPOINT")
ACCESS_KEY = os.getenv("ROCKETMQ_ACCESS_KEY")
SECRET_KEY = os.getenv("ROCKETMQ_SECRET_KEY")

workAgentResponseGroupID = "CID_HOST_AGENT_LITE"
workAgentResponseTopic = "WorkerAgentResponse"

session_id = str(uuid.uuid4())

push_consumer = None
producer = None


class LiteTopicTestMessageListener(MessageListener):

    def consume(self, message: Message) -> ConsumeResult:
        try:
            logger.info("receive msg")
            body = message.body.decode('utf-8')
            payload = MessagePayload.from_json(body)

            # Put payload into async queue for streaming to frontend
            try:
                loop = asyncio.get_running_loop()
                asyncio.create_task(stream_queue_manager.put_payload(payload))
            except RuntimeError:
                if stream_queue_manager.loop:
                    asyncio.run_coroutine_threadsafe(
                        stream_queue_manager.put_payload(payload),
                        stream_queue_manager.loop
                    )
                else:
                    logger.error("No event loop available to process payload")
                    return ConsumeResult.FAILURE

            logging.info("Receive the msginfo " + body)
            return ConsumeResult.SUCCESS
        except Exception as e:
            logger.error(f"Failed to consume message: {e}", exc_info=True)
            return ConsumeResult.FAILURE


class StreamQueueManager:
    """Manages async queues for streaming RocketMQ messages to SSE clients."""

    def __init__(self):
        self.queues = defaultdict(list)
        self.sub_to_main_map = {}
        self.lock = threading.Lock()
        self.loop = None
        self.chat_responses = {}

    def set_loop(self, loop):
        """Set the event loop for async operations."""
        self.loop = loop

    def register_trace(self, trace_id: str) -> Queue:
        """Register a new queue for a trace_id."""
        queue = Queue()
        with self.lock:
            self.queues[trace_id].append(queue)
        logger.info(f"Registered queue for trace_id: {trace_id}")
        return queue

    def register_sub_trace(self, sub_trace_id: str, main_trace_id: str):
        """Map a sub-trace_id to its parent main_trace_id."""
        with self.lock:
            self.sub_to_main_map[sub_trace_id] = main_trace_id
        logger.debug(f"Mapped sub_trace {sub_trace_id} -> main_trace {main_trace_id}")

    def store_chat_response(self, trace_id: str, response: str):
        """Store chat response for later retrieval."""
        with self.lock:
            self.chat_responses[trace_id] = response
        logger.info(f"Stored chat response for trace_id: {trace_id}")

    def get_chat_response(self, trace_id: str) -> Optional[str]:
        """Retrieve and remove chat response."""
        with self.lock:
            return self.chat_responses.pop(trace_id, None)

    async def put_payload(self, payload: MessagePayload):
        """Put payload into all registered queues for this trace_id or its parent."""
        trace_id = payload.trace_id

        with self.lock:
            queues = self.queues.get(trace_id, [])

            if not queues and trace_id in self.sub_to_main_map:
                main_trace_id = self.sub_to_main_map[trace_id]
                queues = self.queues.get(main_trace_id, [])
                logger.debug(f"Routed sub-trace {trace_id} to main_trace {main_trace_id}")

        if queues:
            for queue in queues:
                await queue.put(payload)
                logger.debug(
                    f"Payload sent to queue for trace_id: {trace_id}, chunk_index: {payload.metadata.get('chunk_index', 'N/A')}")
        else:
            logger.warning(f"No queue found for trace_id: {trace_id}")

    def unregister_trace(self, trace_id: str, queue: Queue):
        """Remove a queue from the trace_id registration."""
        with self.lock:
            if trace_id in self.queues:
                if queue in self.queues[trace_id]:
                    self.queues[trace_id].remove(queue)
                if not self.queues[trace_id]:
                    del self.queues[trace_id]

            keys_to_remove = [k for k, v in self.sub_to_main_map.items() if v == trace_id]
            for key in keys_to_remove:
                del self.sub_to_main_map[key]

            self.chat_responses.pop(trace_id, None)

        logger.info(f"Unregistered queue for trace_id: {trace_id}")


stream_queue_manager = StreamQueueManager()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage event loop for stream queue manager."""
    loop = asyncio.get_running_loop()
    stream_queue_manager.set_loop(loop)
    logger.info("Event loop configured for stream queue manager")
    yield


app = FastAPI(lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


def send_message_new(topic: str, payload: MessagePayload):
    """Send message synchronously"""
    try:
        body = payload.to_json()
        msg = build_message(topic=topic, body=body)
        ret = producer.send(msg)
        logger.info(f"[MQ Send] Topic: {topic}, MsgId: {ret.message_id}")
    except Exception as e:
        logger.error(f"[MQ Error] Send failed: {e}")


def init_rocketmq():
    """
    Initialize RocketMQ consumer and producer clients.
    """
    global push_consumer, producer

    try:
        push_consumer = build_lite_push_consumer(
            endpoint=ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY,
            consumer_group=workAgentResponseGroupID,
            topic=workAgentResponseTopic,
            message_listener=LiteTopicTestMessageListener()
        )
        push_consumer.subscribe_lite(session_id)

        producer = build_producer(
            endpoint=ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY
        )
        logger.info("RocketMQ clients initialized successfully")
    except Exception as e:
        logger.error(f"Failed to initialize RocketMQ: {e}", exc_info=True)
        raise


llm_supervisor = ChatTongyi(
    model="qwen-turbo",
    dashscope_api_key=os.getenv("DASHSCOPE_API_KEY"),
    temperature=0.1
)


class AgentState(TypedDict):
    trace_id: str
    user_input: str
    intent: str
    city: str
    date_info: str
    weather_data: str
    final_response: str
    weather_trace_id: Optional[str]
    travel_trace_id: Optional[str]
    weather_complete: bool


result_store = {}
lock = threading.Lock()


def on_result_received(payload: MessagePayload):
    """Store received payloads for aggregation"""
    with lock:
        if payload.trace_id not in result_store:
            result_store[payload.trace_id] = []
        result_store[payload.trace_id].append(payload)


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
    """
    Supervisor logic: Use Qwen model for intent recognition and time extraction
    """
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
    """
    Send MQ task to Weather Agent:
    1. Streaming messages automatically flow to frontend via RocketMQ → StreamQueueManager
    2. Background thread aggregates complete weather data for TravelAgent
    """
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
        bind_topic=workAgentResponseTopic,
        lite_topic=session_id
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
    """
    Wait for weather data aggregation, then send MQ task to Travel Agent with weather info
    """
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
        bind_topic=workAgentResponseTopic,
        lite_topic=session_id
    ))

    return {"travel_trace_id": travel_trace_id}


def chat_node(state: AgentState):
    """
    Directly call LLM for chat responses with streaming support.
    The streaming chunks are sent to frontend via StreamQueueManager.
    """
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
                        import threading
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
                import threading
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
                import threading
                def put_error_in_thread():
                    asyncio.run_coroutine_threadsafe(
                        stream_queue_manager.put_payload(error_payload),
                        stream_queue_manager.loop
                    )
                threading.Thread(target=put_error_in_thread, daemon=True).start()

        return {"final_response": f"Error: {str(e)}"}


def route_after_router(state: AgentState) -> Literal["weather_node", "travel_node", "chat_node"]:
    intent = state.get("intent")
    if intent == "weather":
        return "weather_node"
    elif intent == "travel":
        return "weather_node"
    else:
        return "chat_node"


def route_after_weather(state: AgentState) -> Literal["travel_node", END]:
    intent = state.get("intent")
    if intent == "travel":
        return "travel_node"
    else:
        return END


workflow = StateGraph(AgentState)
workflow.add_node("router", router_node)
workflow.add_node("weather_node", weather_node)
workflow.add_node("travel_node", travel_node)
workflow.add_node("chat_node", chat_node)

workflow.set_entry_point("router")

workflow.add_conditional_edges(
    "router",
    route_after_router,
    {
        "weather_node": "weather_node",
        "travel_node": "weather_node",
        "chat_node": "chat_node",
    }
)

workflow.add_conditional_edges(
    "weather_node",
    route_after_weather,
    {
        "travel_node": "travel_node",
        END: END
    }
)

workflow.add_edge("travel_node", END)
workflow.add_edge("chat_node", END)

app_graph = workflow.compile()


@app.get("/", response_class=HTMLResponse)
def read_root():
    with open("static/index.html", "r", encoding="utf-8") as f:
        return f.read()


@app.post("/chat")
async def chat(request: dict):
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


if __name__ == "__main__":
    import uvicorn

    init_rocketmq()
    logger.info("start supervisor agent successfully")
    uvicorn.run(app, host="0.0.0.0", port=8000)
