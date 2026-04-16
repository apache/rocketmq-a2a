
"""RocketMQ consumer and producer management"""
import asyncio
import logging
from rocketmq import MessageListener, ConsumeResult, Message

from common.models import MessagePayload
from common.mq_toos import logger, build_producer, build_message, build_lite_push_consumer
from supervisor_agent_optimize.my_common.stream.stream_manager import stream_queue_manager
from supervisor_agent_optimize.my_common.config.config import (
    ROCKETMQ_ENDPOINT,
    ROCKETMQ_ACCESS_KEY,
    ROCKETMQ_SECRET_KEY,
    WORK_AGENT_RESPONSE_GROUP_ID,
    WORK_AGENT_RESPONSE_TOPIC,
    SESSION_ID
)

push_consumer = None
producer = None


class WorkerAgentMessageListener(MessageListener):
    """RocketMQ message listener for Worker Agent responses"""

    def consume(self, message: Message) -> ConsumeResult:
        try:
            logger.info("receive msg")
            body = message.body.decode('utf-8')
            payload = MessagePayload.from_json(body)

            # Store in result_store for background aggregation
            from supervisor_agent_optimize.workflow.workflow_nodes import result_store, lock
            with lock:
                if payload.trace_id not in result_store:
                    result_store[payload.trace_id] = []
                result_store[payload.trace_id].append(payload)

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


def send_message_new(topic: str, payload: MessagePayload):
    """Send message synchronously"""
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
    global push_consumer, producer

    try:
        push_consumer = build_lite_push_consumer(
            endpoint=ROCKETMQ_ENDPOINT,
            access_key=ROCKETMQ_ACCESS_KEY,
            secret_key=ROCKETMQ_SECRET_KEY,
            consumer_group=WORK_AGENT_RESPONSE_GROUP_ID,
            topic=WORK_AGENT_RESPONSE_TOPIC,
            message_listener=WorkerAgentMessageListener()
        )
        push_consumer.subscribe_lite(SESSION_ID)

        producer = build_producer(
            endpoint=ROCKETMQ_ENDPOINT,
            access_key=ROCKETMQ_ACCESS_KEY,
            secret_key=ROCKETMQ_SECRET_KEY
        )
        logger.info("RocketMQ clients initialized successfully")
    except Exception as e:
        logger.error(f"Failed to initialize RocketMQ: {e}", exc_info=True)
        raise