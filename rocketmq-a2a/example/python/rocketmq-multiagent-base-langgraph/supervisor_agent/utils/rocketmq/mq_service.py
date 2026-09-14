"""RocketMQ consumer and producer management service"""
import asyncio
from typing import Optional

from rocketmq import MessageListener, ConsumeResult, Message, LitePushConsumer, Producer

from common.model.models import MessagePayload
from common.rocketmq.rocketmq_utils import logger, build_producer, build_message, build_lite_push_consumer, _validate_non_empty
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.config.config import (
    ROCKETMQ_ENDPOINT,
    ROCKETMQ_ACCESS_KEY,
    ROCKETMQ_SECRET_KEY,
    WORK_AGENT_RESPONSE_GROUP_ID,
    WORK_AGENT_RESPONSE_TOPIC,
    SESSION_ID
)

# Global RocketMQ client instances
lite_push_consumer: Optional[LitePushConsumer] = None
producer: Optional[Producer] = None


class WorkerAgentMessageListener(MessageListener):
    """RocketMQ message listener for Worker Agent responses"""

    def consume(self, message: Message) -> ConsumeResult:
        """
        Process incoming messages from Worker Agents.

        Args:
            message: Received RocketMQ message

        Returns:
            ConsumeResult.SUCCESS if processed successfully, FAILURE otherwise
        """
        try:
            # Decode and parse message payload
            body = message.body.decode('utf-8')
            payload = MessagePayload.from_json(body)

            logger.debug(f"Received message - TraceID: {payload.trace_id}, Role: {payload.role}")

            # Store payload in result_store for synchronous aggregation in workflow nodes
            from supervisor_agent.utils.workflow.workflow_nodes import result_store, lock
            with lock:
                if payload.trace_id not in result_store:
                    result_store[payload.trace_id] = []
                result_store[payload.trace_id].append(payload)

            # Forward payload to async queue for real-time SSE streaming
            self._forward_to_stream_queue(payload)

            return ConsumeResult.SUCCESS

        except Exception as e:
            logger.error(f"Failed to consume message: {e}", exc_info=True)
            return ConsumeResult.FAILURE

    def _forward_to_stream_queue(self, payload: MessagePayload) -> None:
        """
        Forward message payload to stream queue for SSE streaming.

        Args:
            payload: Message payload to forward
        """
        try:
            # Try to get current event loop
            loop = asyncio.get_running_loop()
            asyncio.create_task(stream_queue_manager.put_payload(payload))
        except RuntimeError:
            # No running loop, use stream manager's loop if available
            if stream_queue_manager.loop:
                asyncio.run_coroutine_threadsafe(
                    stream_queue_manager.put_payload(payload),
                    stream_queue_manager.loop
                )
            else:
                logger.error("No event loop available to process payload")


def init_rocketmq() -> None:
    """
    Initialize RocketMQ consumer and producer clients.

    Raises:
        Exception: If initialization fails
    """
    global lite_push_consumer, producer

    try:
        # Initialize LitePushConsumer with message listener
        lite_push_consumer = build_lite_push_consumer(
            endpoint=ROCKETMQ_ENDPOINT,
            consumer_group=WORK_AGENT_RESPONSE_GROUP_ID,
            topic=WORK_AGENT_RESPONSE_TOPIC,
            message_listener=WorkerAgentMessageListener(),
            access_key=ROCKETMQ_ACCESS_KEY,
            secret_key=ROCKETMQ_SECRET_KEY
        )

        # Subscribe to default session topic
        lite_push_consumer.subscribe_lite(SESSION_ID)
        logger.info(f"Subscribed to default session: {SESSION_ID}")

        # Initialize message producer
        producer = build_producer(
            endpoint=ROCKETMQ_ENDPOINT,
            access_key=ROCKETMQ_ACCESS_KEY,
            secret_key=ROCKETMQ_SECRET_KEY
        )

        logger.info("RocketMQ clients initialized successfully")

    except Exception as e:
        logger.error(f"Failed to initialize RocketMQ: {e}", exc_info=True)
        raise


def unsubscribe_lite_topic(session_id: str) -> None:
    """
    Unsubscribe from a specific lite topic (session).

    Note: RocketMQ LitePushConsumer doesn't support direct unsubscription.
    Messages will still be received but can be filtered at application level.

    Args:
        session_id: The session ID (lite topic) to unsubscribe from

    Raises:
        ValueError: If session_id is empty or whitespace-only
    """
    global lite_push_consumer

    _validate_non_empty(session_id, "Session ID")

    try:
        if lite_push_consumer is None:
            logger.warning("Push consumer is not initialized, nothing to unsubscribe")
            return
        lite_push_consumer.unsubscribe_lite(session_id)
        logger.info(f"[Unsubscribe] Session ID: {session_id}")

        logger.info(f"Unsubscribe request logged for session: {session_id}")

    except Exception as e:
        logger.error(f"Failed to process unsubscribe request for session {session_id}: {e}", exc_info=True)
        raise


def subscribe_lite_topic(session_id: str) -> None:
    """
    Subscribe to a specific lite topic (session) for real-time message delivery.

    Args:
        session_id: The session ID (lite topic) to subscribe to

    Raises:
        RuntimeError: If push consumer is not initialized
        ValueError: If session_id is empty or whitespace-only
        Exception: If subscription fails
    """
    global lite_push_consumer

    _validate_non_empty(session_id, "Session ID")

    if lite_push_consumer is None:
        error_msg = "Push consumer is not initialized. Call init_rocketmq() first."
        logger.error(error_msg)
        raise RuntimeError(error_msg)

    try:
        logger.info(f"[Subscribe] Session ID: {session_id}")

        # Subscribe to the lite topic for this session
        lite_push_consumer.subscribe_lite(session_id)

        logger.info(f"Successfully subscribed to session: {session_id}")

    except Exception as e:
        logger.error(f"Failed to subscribe to session {session_id}: {e}", exc_info=True)
        raise


def send_message(topic: str, payload: MessagePayload) -> None:
    """
    Send message to RocketMQ topic synchronously.

    Args:
        topic: Target RocketMQ topic
        payload: Message payload to send

    Raises:
        ValueError: If topic or payload is empty/None
        RuntimeError: If producer is not initialized
        Exception: If all retry attempts fail
    """
    global producer

    if not topic or not topic.strip():
        raise ValueError("Topic cannot be empty")

    if payload is None:
        raise ValueError("Payload cannot be None")

    if producer is None:
        raise RuntimeError("Producer is not initialized. Call init_rocketmq() first.")

    max_retries = 3
    last_exception = None

    for attempt in range(1, max_retries + 1):
        try:
            body = payload.to_json()
            msg = build_message(topic=topic, body=body)
            ret = producer.send(msg)
            logger.info(f"[MQ Send] Topic: {topic}, MsgId: {ret.message_id}")
            return
        except Exception as e:
            last_exception = e
            if attempt < max_retries:
                logger.warning(f"[MQ Retry] Send failed (attempt {attempt}/{max_retries}): {e}, retrying in 0.5s...")
                import time
                time.sleep(0.5)
            else:
                logger.error(f"[MQ Error] Send failed after {max_retries} attempts: {e}", exc_info=True)

    raise last_exception
