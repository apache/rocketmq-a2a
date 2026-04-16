
import logging
import os
import json
import signal
import threading

from dotenv import load_dotenv

import dashscope
from rocketmq import MessageListener as RocketMQMessageListener, Message, ConsumeResult

from common.models import MessagePayload, AgentRole
from common.mq_toos import build_push_consumer, build_producer, build_message

# Load environment variables from .env file
load_dotenv()

# Configuration constants
APP_ID = os.getenv("APP_ID_TRAVEL")
TRAVEL_AGENT_TOPIC = "TravelAgentTask"
CONSUMER_GROUP = "TravelAgentTaskConsumerGroup"

# RocketMQ credentials from environment variables
ENDPOINT = os.getenv("ROCKETMQ_ENDPOINT")
ACCESS_KEY = os.getenv("ROCKETMQ_ACCESS_KEY")
SECRET_KEY = os.getenv("ROCKETMQ_SECRET_KEY")

# Global RocketMQ client instances
lite_push_consumer = None
producer = None

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


class TravelMessageListener(RocketMQMessageListener):
    """Message listener for processing travel agent tasks from RocketMQ."""

    def consume(self, message: Message) -> ConsumeResult:
        """
        Process incoming travel task messages.

        Args:
            message: RocketMQ message containing travel task payload

        Returns:
            ConsumeResult.SUCCESS if processed successfully, FAILURE otherwise
        """
        try:
            logger.info(f"Received message: {message}")
            body = message.body.decode('utf-8')
            payload = MessagePayload.from_json(body)
            handle_message(payload)
            return ConsumeResult.SUCCESS
        except Exception as e:
            logger.error(f"Failed to consume message: {e}", exc_info=True)
            return ConsumeResult.FAILURE


def init_rocketmq():
    """
    Initialize RocketMQ consumer and producer clients.

    Sets up global push_consumer for receiving tasks and producer for sending responses.
    Must be called before processing any messages.

    Raises:
        Exception: If initialization fails due to invalid credentials or connection issues
    """
    global lite_push_consumer, producer

    try:
        # Initialize consumer to listen for travel tasks
        push_consumer = build_push_consumer(
            endpoint=ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY,
            consumer_group=CONSUMER_GROUP,
            topic=TRAVEL_AGENT_TOPIC,
            message_listener=TravelMessageListener()
        )

        # Initialize producer to send response messages
        producer = build_producer(
            endpoint=ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY
        )

        logger.info("RocketMQ clients initialized successfully")
    except Exception as e:
        logger.error(f"Failed to initialize RocketMQ: {e}", exc_info=True)
        raise


def send_message(topic: str, payload: MessagePayload, lite_topic: str):
    """
    Send a response message via RocketMQ.

    Args:
        topic: Target topic to send the message to
        payload: Message payload containing response data
        lite_topic: Lite topic for message routing

    Raises:
        RuntimeError: If producer is not initialized
        Exception: If message sending fails
    """
    if producer is None:
        raise RuntimeError("Producer not initialized. Call init_rocketmq() first.")

    try:
        body = payload.to_json()
        msg = build_message(topic=topic, body=body, lite_topic=lite_topic)
        ret = producer.send(msg)
        logger.info(f"Message sent successfully. Topic: {topic}, MsgId: {ret.message_id}")
    except Exception as e:
        logger.error(f"Failed to send message: {e}", exc_info=True)
        raise


def call_bailian_travel_app_streaming(request: str, date_info: str, weather_info: str, payload: MessagePayload):
    """
    Call Bailian AI application to generate travel itinerary with streaming output.
    Takes into account weather information to create a weather-aware travel plan.
    Only incremental text is sent via RocketMQ in real-time.

    Args:
        request: Original user request for travel planning
        date_info: Travel date information
        weather_info: Weather information from weather agent
        payload: Original message payload for trace_id and topic info
    """
    prompt = f"""请根据以下信息制定旅行行程规划：

用户需求：{request}
出行日期：{date_info}
天气情况：{weather_info}

请结合天气情况，为用户提供合理的行程安排建议。如果天气不佳，请提供室内活动备选方案。行程应包括：
1. 每日详细时间安排
2. 景点推荐（考虑天气因素）
3. 交通建议
4. 餐饮推荐
5. 注意事项和温馨提示
6. 雨天/恶劣天气的备选方案"""

    try:
        response = dashscope.Application.call(
            api_key=os.getenv("DASHSCOPE_API_KEY"),
            app_id=APP_ID,
            prompt=prompt,
            stream=True
        )

        chunk_count = 0
        previous_text = ""

        for chunk in response:
            if chunk.status_code == 200:
                current_text = chunk.output.text

                # Calculate incremental text (delta)
                incremental_text = current_text[len(previous_text):]

                if incremental_text:  # Only send if there's new content
                    chunk_count += 1
                    logger.info(f"Stream chunk {chunk_count} received (incremental): {incremental_text}")

                    # Send only the incremental text via RocketMQ
                    chunk_payload = MessagePayload(
                        trace_id=payload.trace_id,
                        role=AgentRole.TRAVEL,
                        content=incremental_text,
                        bind_topic=None,
                        lite_topic=None,
                        metadata={"chunk_index": chunk_count, "is_final": False}
                    )
                    send_message(payload.bind_topic, chunk_payload, payload.lite_topic)

                # Update previous text for next comparison
                previous_text = current_text

            else:
                error_msg = f"Bailian API call failed: {chunk.message}"
                logger.error(error_msg)

                error_payload = MessagePayload(
                    trace_id=payload.trace_id,
                    role=AgentRole.TRAVEL,
                    content=error_msg,
                    bind_topic=None,
                    lite_topic=None,
                    metadata={"chunk_index": chunk_count, "is_final": True, "error": True}
                )
                send_message(payload.bind_topic, error_payload, payload.lite_topic)
                return

        logger.info(f"Streaming completed. Total chunks sent: {chunk_count}")

        # Send final marker message
        final_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.TRAVEL,
            content="",
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": chunk_count, "is_final": True}
        )
        send_message(payload.bind_topic, final_payload, payload.lite_topic)

    except Exception as e:
        error_msg = f"Exception occurred while calling Bailian API: {str(e)}"
        logger.error(error_msg, exc_info=True)

        error_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.TRAVEL,
            content=error_msg,
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": 0, "is_final": True, "error": True}
        )
        send_message(payload.bind_topic, error_payload, payload.lite_topic)


def handle_message(payload: MessagePayload):
    """
    Process travel task message and send back the result using streaming.

    Extracts request, date, and weather_info from payload, calls Bailian AI to generate
    a weather-aware travel itinerary with streaming output, and sends each chunk back
    to the specified topic.

    Args:
        payload: Message payload containing travel task details with weather information
    """
    try:
        # Parse message content
        data = json.loads(payload.content)
        request = data.get("request", "")
        date_info = data.get("date", "近期")
        weather_info = data.get("weather_info", "天气信息未知")

        logger.info(f"Processing travel request: {request}")
        logger.info(f"Date: {date_info}, Weather info length: {len(weather_info)} chars")

        # Generate weather-aware travel itinerary using Bailian AI with streaming
        call_bailian_travel_app_streaming(request, date_info, weather_info, payload)

        logger.info(f"Travel itinerary streaming completed for trace_id: {payload.trace_id}")

    except Exception as e:
        logger.error(f"Error processing travel request: {e}", exc_info=True)

        # Send error response
        error_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.TRAVEL,
            content=f"Processing failed: {str(e)}",
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": 0, "is_final": True, "error": True}
        )
        send_message(payload.bind_topic, error_payload, payload.lite_topic)


def shutdown():
    """Gracefully shutdown RocketMQ clients."""
    logger.info("Shutting down RocketMQ clients...")

    if lite_push_consumer:
        try:
            lite_push_consumer.shutdown()
            logger.info("Push consumer shutdown successfully")
        except Exception as e:
            logger.error(f"Error shutting down consumer: {e}")

    if producer:
        try:
            producer.shutdown()
            logger.info("Producer shutdown successfully")
        except Exception as e:
            logger.error(f"Error shutting down producer: {e}")


if __name__ == "__main__":
    logger.info("Starting Travel Agent...")

    shutdown_event = threading.Event()


    def signal_handler(signum, frame):
        logger.info(f"Received signal {signum}")
        shutdown_event.set()


    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        init_rocketmq()
        logger.info("Travel Agent started successfully")

        shutdown_event.wait()

    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
    finally:
        shutdown()
        logger.info("Travel Agent stopped")
