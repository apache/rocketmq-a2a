
"""RocketMQ consumer and producer management"""
import asyncio
import logging
from typing import Optional

from rocketmq import MessageListener, ConsumeResult, Message, LitePushConsumer

from common.models import MessagePayload
from common.mq_toos import logger, build_producer, build_message, build_lite_push_consumer
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.config.config import (
    ROCKETMQ_ENDPOINT,
    ROCKETMQ_ACCESS_KEY,
    ROCKETMQ_SECRET_KEY,
    WORK_AGENT_RESPONSE_GROUP_ID,
    WORK_AGENT_RESPONSE_TOPIC
)

# Global RocketMQ client instances
lite_push_consumer: Optional[LitePushConsumer] = None
producer = None


class WorkerAgentMessageListener(MessageListener):
    """RocketMQ message listener for Worker Agent responses"""

    def consume(self, message: Message) -> ConsumeResult:
        """Process incoming messages from Worker Agents"""
        try:
            logger.info("receive msg")
            body = message.body.decode('utf-8')
            payload = MessagePayload.from_json(body)
            # 注意 这里的trace_id 为 每次发出的请求 生成的trace_id
            # Store payload in result_store for synchronous aggregation in workflow nodes
            from supervisor_agent.utils.workflow.workflow_nodes import result_store, lock
            with lock:
                if payload.trace_id not in result_store:
                    result_store[payload.trace_id] = []
                result_store[payload.trace_id].append(payload)

            # Forward payload to async queue for real-time SSE streaming to frontend
            try:
                loop = asyncio.get_running_loop()
                asyncio.create_task(stream_queue_manager.put_payload(payload))
            except RuntimeError:
                # Fallback: run coroutine in existing event loop from another thread
                if stream_queue_manager.loop:
                    asyncio.run_coroutine_threadsafe(
                        stream_queue_manager.put_payload(payload),
                        stream_queue_manager.loop
                    )
                else:
                    logger.error("No event loop available to process payload")
                    return ConsumeResult.FAILURE

            # logging.info("Receive the msginfo " + body)
            return ConsumeResult.SUCCESS
        except Exception as e:
            logger.error(f"Failed to consume message: {e}", exc_info=True)
            return ConsumeResult.FAILURE


def send_message(topic: str, payload: MessagePayload):
    """Send message to RocketMQ topic synchronously"""
    global producer
    try:
        body = payload.to_json()
        msg = build_message(topic=topic, body=body)
        ret = producer.send(msg)
        logger.info(f"[MQ Send] Topic: {topic}, MsgId: {ret.message_id}")
    except Exception as e:
        logger.error(f"[MQ Error] Send failed: {e}")


def init_rocketmq():
    """Initialize RocketMQ consumer and producer clients"""
    global lite_push_consumer, producer

    try:
        # Build and configure LitePushConsumer with message listener
        lite_push_consumer = build_lite_push_consumer(
            endpoint=ROCKETMQ_ENDPOINT,
            access_key=ROCKETMQ_ACCESS_KEY,
            secret_key=ROCKETMQ_SECRET_KEY,
            consumer_group=WORK_AGENT_RESPONSE_GROUP_ID,
            topic=WORK_AGENT_RESPONSE_TOPIC,
            message_listener=WorkerAgentMessageListener()
        )
        # Note: Session-specific subscriptions are added dynamically via subscribe_lite_topic()

        # Build and configure message producer
        producer = build_producer(
            endpoint=ROCKETMQ_ENDPOINT,
            access_key=ROCKETMQ_ACCESS_KEY,
            secret_key=ROCKETMQ_SECRET_KEY
        )
        logger.info("RocketMQ clients initialized successfully")
    except Exception as e:
        logger.error(f"Failed to initialize RocketMQ: {e}", exc_info=True)
        raise


def unsubscribe_lite_topic(session_id: str):
    """
    Unsubscribe from a specific lite topic (session).

    Note: RocketMQ LitePushConsumer doesn't support direct unsubscription.
    This method logs the intent to unsubscribe for the given session.

    Args:
        session_id: The session ID (lite topic) to unsubscribe from
    """
    global lite_push_consumer

    try:
        if lite_push_consumer is None:
            logger.warning("Push consumer is not initialized, nothing to unsubscribe")
            return

        logger.info(f"[Unsubscribe] Session ID: {session_id}")
        logger.info(f"[Unsubscribe] Current consumer status: active")

        # Note: LitePushConsumer doesn't have a direct unsubscribe method
        # To truly unsubscribe, you would need to:
        # 1. Shutdown the current consumer: push_consumer.shutdown()
        # 2. Recreate it without the session_id subscription
        #
        # For now, we just log the unsubscribe request
        # If you need actual unsubscription, implement consumer recreation logic here

        logger.info(f"Unsubscribe request logged for session: {session_id}")

    except Exception as e:
        logger.error(f"Failed to process unsubscribe request for session {session_id}: {e}", exc_info=True)
        raise


def subscribe_lite_topic(session_id: str):
    """
    Subscribe to a specific lite topic (session) for real-time message delivery.

    Args:
        session_id: The session ID (lite topic) to subscribe to

    Raises:
        RuntimeError: If push consumer is not initialized
        Exception: If subscription fails
    """
    global lite_push_consumer

    try:
        if lite_push_consumer is None:
            error_msg = "Push consumer is not initialized. Call init_rocketmq() first."
            logger.error(error_msg)
            raise RuntimeError(error_msg)

        logger.info(f"[Subscribe] Session ID: {session_id}")

        # Subscribe to the lite topic for this session
        lite_push_consumer.subscribe_lite(session_id)

        logger.info(f"Successfully subscribed to session: {session_id}")

    except RuntimeError:
        # Re-raise RuntimeError as-is
        raise
    except Exception as e:
        logger.error(f"Failed to subscribe to session {session_id}: {e}", exc_info=True)
        raise
