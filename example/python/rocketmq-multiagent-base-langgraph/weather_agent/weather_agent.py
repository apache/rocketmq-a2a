import logging
import os
import json
import signal
import threading
import time

from dotenv import load_dotenv

import dashscope
from rocketmq import MessageListener as RocketMQMessageListener, Message, ConsumeResult

from common.models import MessagePayload, AgentRole
from common.rocketmq_utils import build_push_consumer, build_producer, build_message

# Load environment variables from .env file
load_dotenv()

# Configuration constants
APP_ID = os.getenv("APP_ID_WEATHER")
WEATHER_AGENT_TOPIC = "WeatherAgentTask"
CONSUMER_GROUP = "WeatherAgentTaskConsumerGroup"

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


class WeatherMessageListener(RocketMQMessageListener):
    """Message listener for processing weather agent tasks from RocketMQ."""

    def consume(self, message: Message) -> ConsumeResult:
        """
        Process incoming weather task messages.

        Args:
            message: RocketMQ message containing weather task payload

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
        # Initialize consumer to listen for weather tasks
        push_consumer = build_push_consumer(
            endpoint=ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY,
            consumer_group=CONSUMER_GROUP,
            topic=WEATHER_AGENT_TOPIC,
            message_listener=WeatherMessageListener()
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


def call_bailian_weather_app_streaming(city: str, date_info: str, payload: MessagePayload):
    """
    Call Bailian AI application to query weather information with streaming output.
    Only incremental text is sent via RocketMQ in real-time.

    Args:
        city: City name to query weather for
        date_info: Target date for weather query
        payload: Original message payload for trace_id and topic info
    """
    prompt = f"请查询 {city} 在 {date_info} 的天气情况。"

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

                    # Create chunk payload
                    chunk_payload = MessagePayload(
                        trace_id=payload.trace_id,
                        role=AgentRole.WEATHER,
                        content=incremental_text,
                        bind_topic=None,
                        lite_topic=None,
                        metadata={"chunk_index": chunk_count, "is_final": False}
                    )

                    # Send with retry mechanism (max 3 attempts)
                    send_success = False
                    for attempt in range(3):
                        try:
                            send_message(payload.bind_topic, chunk_payload, payload.lite_topic)
                            send_success = True
                            logger.debug(f"Chunk {chunk_count} sent successfully on attempt {attempt + 1}")
                            break
                        except Exception as e:
                            logger.warning(f"Failed to send chunk {chunk_count} (attempt {attempt + 1}/3): {str(e)}")
                            if attempt < 2:  # Not the last attempt
                                time.sleep(0.5)  # Wait before retry

                    if not send_success:
                        logger.error(f"Failed to send chunk {chunk_count} after 3 attempts, skipping to next chunk")

                # Update previous text for next comparison
                previous_text = current_text

            else:
                error_msg = f"Bailian API call failed: {chunk.message}"
                logger.error(error_msg)

                error_payload = MessagePayload(
                    trace_id=payload.trace_id,
                    role=AgentRole.WEATHER,
                    content=error_msg,
                    bind_topic=None,
                    lite_topic=None,
                    metadata={"chunk_index": chunk_count, "is_final": True, "error": True}
                )

                # Send error with retry
                for attempt in range(3):
                    try:
                        send_message(payload.bind_topic, error_payload, payload.lite_topic)
                        logger.debug(f"Error payload sent successfully on attempt {attempt + 1}")
                        break
                    except Exception as e:
                        logger.warning(f"Failed to send error payload (attempt {attempt + 1}/3): {str(e)}")
                        if attempt < 2:
                            time.sleep(0.5)

                return

        logger.info(f"Streaming completed. Total chunks sent: {chunk_count}")

        # Send final marker message
        final_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.WEATHER,
            content="",
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": chunk_count, "is_final": True}
        )

        # Send final marker with retry
        for attempt in range(3):
            try:
                send_message(payload.bind_topic, final_payload, payload.lite_topic)
                logger.debug(f"Final marker sent successfully on attempt {attempt + 1}")
                break
            except Exception as e:
                logger.warning(f"Failed to send final marker (attempt {attempt + 1}/3): {str(e)}")
                if attempt < 2:
                    time.sleep(0.5)

    except Exception as e:
        error_msg = f"Exception occurred while calling Bailian API: {str(e)}"
        logger.error(error_msg, exc_info=True)

        error_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.WEATHER,
            content=error_msg,
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": 0, "is_final": True, "error": True}
        )

        # Send exception error with retry
        for attempt in range(3):
            try:
                send_message(payload.bind_topic, error_payload, payload.lite_topic)
                logger.debug(f"Exception error payload sent successfully on attempt {attempt + 1}")
                break
            except Exception as send_e:
                logger.warning(f"Failed to send exception error payload (attempt {attempt + 1}/3): {str(send_e)}")
                if attempt < 2:
                    time.sleep(0.5)


def handle_message(payload: MessagePayload):
    """
    Process weather task message and send back the result using streaming.

    Extracts city and date from payload, calls Bailian AI to get weather info
    with streaming output, and sends each chunk back to the specified topic.

    Args:
        payload: Message payload containing weather task details
    """
    try:
        # Parse message content
        data = json.loads(payload.content)
        city = data.get("city", "")
        date_info = data.get("date", "今天")

        logger.info(f"Processing weather request for city: {city}, date: {date_info}")

        # Query weather using Bailian AI with streaming
        call_bailian_weather_app_streaming(city, date_info, payload)

        logger.info(f"Weather streaming completed for trace_id: {payload.trace_id}")

    except Exception as e:
        logger.error(f"Error processing weather request: {e}", exc_info=True)

        # Send error response
        error_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.WEATHER,
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
    logger.info("Starting Weather Agent...")

    shutdown_event = threading.Event()


    def signal_handler(signum, frame):
        logger.info(f"Received signal {signum}")
        shutdown_event.set()


    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        init_rocketmq()
        logger.info("Weather Agent started successfully")

        shutdown_event.wait()

    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
    finally:
        shutdown()
        logger.info("Weather Agent stopped")
