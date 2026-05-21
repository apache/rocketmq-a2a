"""RocketMQ client management for Travel Agent"""
import logging
from typing import Optional

from rocketmq import MessageListener as RocketMQMessageListener, Message, ConsumeResult

from common.model.models import MessagePayload
from common.rocketmq.rocketmq_utils import build_push_consumer, build_producer, build_message
from travel_agent.config.config import ENDPOINT, ACCESS_KEY, SECRET_KEY, CONSUMER_GROUP, TRAVEL_AGENT_TOPIC

logger = logging.getLogger(__name__)

# Global RocketMQ client instances
lite_push_consumer: Optional[object] = None
producer: Optional[object] = None


class TravelMessageListener(RocketMQMessageListener):
    """Message listener for processing travel agent tasks from RocketMQ."""

    def __init__(self, message_handler):
        """
        Initialize message listener.

        Args:
            message_handler: Callable to handle incoming messages
        """
        self.message_handler = message_handler

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
            self.message_handler(payload)
            return ConsumeResult.SUCCESS
        except Exception as e:
            logger.error(f"Failed to consume message: {e}", exc_info=True)
            return ConsumeResult.FAILURE


def init_rocketmq(message_handler) -> None:
    """
    Initialize RocketMQ consumer and producer clients.

    Args:
        message_handler: Callable to handle incoming messages

    Raises:
        Exception: If initialization fails
    """
    global lite_push_consumer, producer

    try:
        # Initialize consumer to listen for travel tasks
        lite_push_consumer = build_push_consumer(
            endpoint=ENDPOINT,
            access_key=ACCESS_KEY,
            secret_key=SECRET_KEY,
            consumer_group=CONSUMER_GROUP,
            topic=TRAVEL_AGENT_TOPIC,
            message_listener=TravelMessageListener(message_handler)
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


def send_message(topic: str, payload: MessagePayload, lite_topic: str) -> None:
    """
    Send a response message via RocketMQ.

    Args:
        topic: Target topic to send the message to
        payload: Message payload containing response data
        lite_topic: Lite topic for message routing

    Raises:
        ValueError: If topic, payload, or lite_topic is empty/None
        RuntimeError: If producer is not initialized
        Exception: If all retry attempts fail
    """
    if not topic or not topic.strip():
        raise ValueError("Topic cannot be empty")

    if payload is None:
        raise ValueError("Payload cannot be None")

    if not lite_topic or not lite_topic.strip():
        raise ValueError("Lite topic cannot be empty")

    if producer is None:
        raise RuntimeError("Producer not initialized. Call init_rocketmq() first.")

    max_retries = 3
    last_exception = None

    for attempt in range(1, max_retries + 1):
        try:
            body = payload.to_json()
            msg = build_message(topic=topic, body=body, lite_topic=lite_topic)
            ret = producer.send(msg)
            logger.debug(f"Message sent - Topic: {topic}, MsgId: {ret.message_id}")
            return
        except Exception as e:
            last_exception = e
            if attempt < max_retries:
                logger.warning(f"Send failed (attempt {attempt}/{max_retries}): {e}, retrying in 0.5s...")
                import time
                time.sleep(0.5)
            else:
                logger.error(f"Failed to send message after {max_retries} attempts: {e}", exc_info=True)

    raise last_exception


def shutdown() -> None:
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
