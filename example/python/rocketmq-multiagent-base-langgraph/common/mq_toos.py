import json
import logging
from typing import Optional

from rocketmq import (
    ClientConfiguration,
    ConsumeResult,
    Credentials,
    FilterExpression,
    LitePushConsumer,
    Message,
    MessageListener,
    Producer,
    PushConsumer,
)

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def build_producer(
        endpoint: str,
        access_key: str,
        secret_key: str,
        group_id: str = "GID_PYTHON_PRODUCER_DEFAULT",
) -> Producer:
    """
    Build and start a RocketMQ producer.

    Args:
        endpoint: RocketMQ service endpoint
        access_key: Access key for authentication
        secret_key: Secret key for authentication
        group_id: Producer group ID (default: GID_PYTHON_PRODUCER_DEFAULT)

    Returns:
        Started Producer instance

    Raises:
        ValueError: If required parameters are invalid
        Exception: If producer fails to start
    """
    # Validate required parameters
    if not endpoint or not endpoint.strip():
        logger.warning("Invalid endpoint: [%s]", endpoint)
        raise ValueError("Endpoint cannot be empty")

    if not access_key or not access_key.strip():
        logger.warning("Invalid access_key")
        raise ValueError("Access key cannot be empty")

    if not secret_key or not secret_key.strip():
        logger.warning("Invalid secret_key")
        raise ValueError("Secret key cannot be empty")

    try:
        # Create credentials and configuration
        credentials = Credentials(access_key, secret_key)
        config = ClientConfiguration(endpoint, credentials)

        # Initialize and start producer
        producer = Producer(client_configuration=config)
        producer.startup()

        logger.info("Producer started successfully. Endpoint: [%s], Group: [%s]", endpoint, group_id)
        return producer

    except Exception as e:
        logger.error("Failed to start Producer: %s", str(e))
        raise


class SimpleMessageListener(MessageListener):
    """Simple message listener that logs received messages."""

    def consume(self, message: Message) -> ConsumeResult:
        """
        Process incoming message.

        Args:
            message: Received message from RocketMQ

        Returns:
            ConsumeResult.SUCCESS to acknowledge successful consumption
        """
        logger.info("Received message: %s", message)
        return ConsumeResult.SUCCESS


def build_lite_push_consumer(
        endpoint: str,
        access_key: str,
        secret_key: str,
        consumer_group: str,
        topic: str,
        message_listener: Optional[MessageListener] = None,
) -> LitePushConsumer:
    """
    Build and start a RocketMQ lite push consumer.

    Args:
        endpoint: RocketMQ service endpoint
        access_key: Access key for authentication
        secret_key: Secret key for authentication
        consumer_group: Consumer group ID
        topic: Topic to subscribe to
        message_listener: Custom message listener (uses SimpleMessageListener if None)

    Returns:
        Started LitePushConsumer instance

    Raises:
        ValueError: If required parameters are invalid
        Exception: If consumer fails to start
    """
    # Validate required parameters
    if not endpoint or not endpoint.strip():
        logger.warning("Invalid endpoint: [%s]", endpoint)
        raise ValueError("Endpoint cannot be empty")

    if not consumer_group or not consumer_group.strip():
        logger.warning("Invalid consumer_group: [%s]", consumer_group)
        raise ValueError("Consumer group cannot be empty")

    if not topic or not topic.strip():
        logger.warning("Invalid topic: [%s]", topic)
        raise ValueError("Topic cannot be empty")

    # Use default listener if not provided
    if message_listener is None:
        message_listener = SimpleMessageListener()

    try:
        # Create credentials and configuration
        credentials = Credentials(access_key, secret_key)
        config = ClientConfiguration(endpoint, credentials)

        # Initialize and start lite push consumer
        consumer = LitePushConsumer(
            client_configuration=config,
            consumer_group=consumer_group,
            bind_topic=topic,
            message_listener=message_listener,
        )
        consumer.startup()

        logger.info(
            "LitePushConsumer started successfully. Group: [%s], Topic: [%s], Endpoint: [%s]",
            consumer_group,
            topic,
            endpoint,
        )
        return consumer

    except Exception as e:
        logger.error("Failed to start LitePushConsumer: %s", str(e))
        raise


def build_push_consumer(
        endpoint: str,
        access_key: str,
        secret_key: str,
        consumer_group: str,
        topic: str,
        message_listener: MessageListener,
        tag_expression: Optional[str] = None,
) -> PushConsumer:
    """
    Build and start a RocketMQ push consumer with subscription support.

    Args:
        endpoint: RocketMQ service endpoint
        access_key: Access key for authentication
        secret_key: Secret key for authentication
        consumer_group: Consumer group ID
        topic: Topic to subscribe to
        message_listener: Message listener to handle incoming messages
        tag_expression: Optional tag filter expression (e.g., "TagA || TagB")

    Returns:
        Started PushConsumer instance

    Raises:
        ValueError: If required parameters are invalid
        Exception: If consumer fails to start
    """
    # Validate required parameters
    if not endpoint or not endpoint.strip():
        logger.warning("Invalid endpoint: [%s]", endpoint)
        raise ValueError("Endpoint cannot be empty")

    if not consumer_group or not consumer_group.strip():
        logger.warning("Invalid consumer_group: [%s]", consumer_group)
        raise ValueError("Consumer group cannot be empty")

    if not topic or not topic.strip():
        logger.warning("Invalid topic: [%s]", topic)
        raise ValueError("Topic cannot be empty")

    if message_listener is None:
        logger.warning("Invalid message_listener: None")
        raise ValueError("Message listener cannot be None")

    try:
        # Create credentials and configuration
        credentials = Credentials(access_key, secret_key)
        config = ClientConfiguration(endpoint, credentials)

        # Initialize push consumer
        consumer = PushConsumer(client_configuration=config, consumer_group=consumer_group,
                                message_listener=message_listener, subscription={topic: FilterExpression(), })
        # Start consumer
        consumer.startup()
        logger.info(
            "PushConsumer started successfully. Group: [%s], Topic: [%s], Endpoint: [%s]",
            consumer_group,
            topic,
            endpoint,
        )
        return consumer

    except Exception as e:
        logger.error("Failed to start PushConsumer: %s", str(e))
        raise


def build_message(topic: str, body: str, keys: Optional[list] = None, tags: Optional[str] = None,
                  lite_topic: Optional[str] = None) -> Message:
    """
    Build a RocketMQ message.

    Args:
        topic: Message topic
        body: Message body as dictionary (will be JSON serialized)
        keys: Optional message keys for indexing
        tags: Optional message tags for filtering

    Returns:
        Configured Message instance ready to send

    Raises:
        ValueError: If required parameters are invalid
        Exception: If message building fails
        :param lite_topic:
    """
    # Validate required parameters
    if not topic or not topic.strip():
        logger.warning("Invalid topic: [%s]", topic)
        raise ValueError("Topic cannot be empty")
    try:
        # Serialize body to JSON bytes
        body_bytes = body.encode("utf-8")

        # Create and configure message
        msg = Message()
        msg.topic = topic
        msg.body = body_bytes
        if lite_topic and lite_topic.strip():
            msg.lite_topic = lite_topic.strip()

        # Set optional properties
        if keys:
            msg.keys = keys
            logger.debug("Set message keys: %s", keys)

        if tags:
            msg.tags = tags
            logger.debug("Set message tags: %s", tags)

        logger.debug("Message built for topic: [%s], size: %d bytes", topic, len(body_bytes))
        return msg

    except Exception as e:
        logger.error("Failed to build message: %s", str(e))
        raise
