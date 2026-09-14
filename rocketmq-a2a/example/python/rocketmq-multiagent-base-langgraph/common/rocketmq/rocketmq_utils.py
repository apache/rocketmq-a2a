import logging
from typing import Optional, List

from rocketmq import (
    ClientConfiguration,
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


def _validate_non_empty(value: str, param_name: str) -> None:
    """
    Validate that a string parameter is not empty.

    Args:
        value: String value to validate
        param_name: Parameter name for error messages

    Raises:
        ValueError: If value is empty or whitespace-only
    """
    if not value or not value.strip():
        logger.warning("Invalid %s: [%s]", param_name, value)
        raise ValueError(f"{param_name} cannot be empty")


def build_producer(
        endpoint: str,
        access_key: Optional[str] = None,
        secret_key: Optional[str] = None
) -> Producer:
    """
    Build and start a RocketMQ producer.

    Args:
        endpoint: RocketMQ service endpoint
        access_key: Optional access key for authentication
        secret_key: Optional secret key for authentication

    Returns:
        Started Producer instance

    Raises:
        ValueError: If required parameters are invalid
        Exception: If producer fails to start
    """
    # Validate required parameters
    _validate_non_empty(endpoint, "Endpoint")

    try:
        # Create configuration with optional credentials
        if access_key and secret_key:
            credentials = Credentials(access_key, secret_key)
            config = ClientConfiguration(endpoint, credentials)
            logger.info("Using authenticated connection with credentials")
        else:
            config = ClientConfiguration(endpoint)
            logger.info("Using unauthenticated connection (no credentials provided)")

        # Initialize and start producer
        producer = Producer(client_configuration=config)
        producer.startup()

        logger.info("Producer started successfully. Endpoint: [%s]", endpoint)
        return producer

    except Exception as e:
        logger.error("Failed to start Producer: %s", str(e))
        raise


def build_lite_push_consumer(
        endpoint: str,
        consumer_group: str,
        topic: str,
        message_listener: MessageListener,
        access_key: Optional[str] = None,
        secret_key: Optional[str] = None,
) -> LitePushConsumer:
    """
    Build and start a RocketMQ lite push consumer.

    Args:
        endpoint: RocketMQ service endpoint
        consumer_group: Consumer group ID
        topic: Topic to subscribe to
        message_listener: Custom message listener to handle incoming messages
        access_key: Optional access key for authentication
        secret_key: Optional secret key for authentication

    Returns:
        Started LitePushConsumer instance

    Raises:
        ValueError: If required parameters are invalid
        Exception: If consumer fails to start
    """
    # Validate required parameters
    _validate_non_empty(endpoint, "Endpoint")
    _validate_non_empty(consumer_group, "Consumer group")
    _validate_non_empty(topic, "Topic")

    # Validate message listener is provided
    if message_listener is None:
        logger.warning("Invalid message_listener: None")
        raise ValueError("Message listener cannot be None")

    try:
        # Create configuration with optional credentials
        if access_key and secret_key:
            credentials = Credentials(access_key, secret_key)
            config = ClientConfiguration(endpoint, credentials)
            logger.info("Using authenticated connection with credentials")
        else:
            config = ClientConfiguration(endpoint)
            logger.info("Using unauthenticated connection (no credentials provided)")

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
        consumer_group: str,
        topic: str,
        message_listener: MessageListener,
        access_key: Optional[str] = None,
        secret_key: Optional[str] = None,
        tag_expression: Optional[str] = None,
) -> PushConsumer:
    """
    Build and start a RocketMQ push consumer with subscription support.

    Args:
        endpoint: RocketMQ service endpoint
        consumer_group: Consumer group ID
        topic: Topic to subscribe to
        message_listener: Message listener to handle incoming messages
        access_key: Optional access key for authentication
        secret_key: Optional secret key for authentication
        tag_expression: Optional tag filter expression (e.g., "TagA || TagB")

    Returns:
        Started PushConsumer instance

    Raises:
        ValueError: If required parameters are invalid
        Exception: If consumer fails to start
    """
    # Validate required parameters
    _validate_non_empty(endpoint, "Endpoint")
    _validate_non_empty(consumer_group, "Consumer group")
    _validate_non_empty(topic, "Topic")

    # Validate message listener is provided
    if message_listener is None:
        logger.warning("Invalid message_listener: None")
        raise ValueError("Message listener cannot be None")

    try:
        # Create configuration with optional credentials
        if access_key and secret_key:
            credentials = Credentials(access_key, secret_key)
            config = ClientConfiguration(endpoint, credentials)
            logger.info("Using authenticated connection with credentials")
        else:
            config = ClientConfiguration(endpoint)
            logger.info("Using unauthenticated connection (no credentials provided)")

        # Initialize push consumer with subscription
        consumer = PushConsumer(
            client_configuration=config,
            consumer_group=consumer_group,
            message_listener=message_listener,
            subscription={topic: FilterExpression()},
        )

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


def build_message(
        topic: str,
        body: str,
        keys: Optional[List[str]] = None,
        tags: Optional[str] = None,
        lite_topic: Optional[str] = None
) -> Message:
    """
    Build a RocketMQ message.

    Args:
        topic: Message topic
        body: Message body as string (already serialized)
        keys: Optional message keys for indexing
        tags: Optional message tags for filtering
        lite_topic: Optional lightweight topic for session-based routing

    Returns:
        Configured Message instance ready to send

    Raises:
        ValueError: If required parameters are invalid
        Exception: If message building fails
    """
    # Validate required parameters
    _validate_non_empty(topic, "Topic")
    _validate_non_empty(body, "Body")

    try:
        # Encode body to UTF-8 bytes
        body_bytes = body.encode("utf-8")

        # Create and configure message
        msg = Message()
        msg.topic = topic
        msg.body = body_bytes

        # Set lightweight topic if provided
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

