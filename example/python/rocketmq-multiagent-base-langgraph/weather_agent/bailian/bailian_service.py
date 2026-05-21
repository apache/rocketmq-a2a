"""Bailian AI service for weather information queries"""
import logging
import time

import dashscope

from common.model.models import MessagePayload, AgentRole
from weather_agent.config.config import DASHSCOPE_API_KEY, APP_ID
from weather_agent.rocketmq.mq_service import send_message

logger = logging.getLogger(__name__)

# Retry configuration
MAX_RETRY_ATTEMPTS = 3
RETRY_DELAY_SECONDS = 0.5


def send_with_retry(topic: str, payload: MessagePayload, lite_topic: str, operation_name: str = "message") -> bool:
    """
    Send message with retry mechanism.

    Args:
        topic: Target topic
        payload: Message payload
        lite_topic: Lite topic for routing
        operation_name: Name of operation for logging

    Returns:
        True if sent successfully, False otherwise
    """
    for attempt in range(MAX_RETRY_ATTEMPTS):
        try:
            send_message(topic, payload, lite_topic)
            logger.debug(f"{operation_name.capitalize()} sent successfully on attempt {attempt + 1}")
            return True
        except Exception as e:
            logger.warning(f"Failed to send {operation_name} (attempt {attempt + 1}/{MAX_RETRY_ATTEMPTS}): {e}")
            if attempt < MAX_RETRY_ATTEMPTS - 1:
                time.sleep(RETRY_DELAY_SECONDS)

    logger.error(f"Failed to send {operation_name} after {MAX_RETRY_ATTEMPTS} attempts")
    return False


def query_weather_streaming(city: str, date_info: str, payload: MessagePayload) -> None:
    """
    Call Bailian AI to query weather information with streaming output.
    Delivers incremental text chunks in real-time via RocketMQ.

    Args:
        city: City name to query weather for
        date_info: Target date for weather query
        payload: Original message payload for trace_id and topic info
    """
    prompt = f"请查询 {city} 在 {date_info} 的天气情况。"

    try:
        response = dashscope.Application.call(
            api_key=DASHSCOPE_API_KEY,
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
                    logger.debug(f"Stream chunk {chunk_count} received: {incremental_text[:50]}...")

                    # Create chunk payload
                    chunk_payload = MessagePayload(
                        trace_id=payload.trace_id,
                        role=AgentRole.WEATHER,
                        content=incremental_text,
                        bind_topic=None,
                        lite_topic=None,
                        metadata={"chunk_index": chunk_count, "is_final": False}
                    )

                    # Send chunk with retry mechanism
                    send_with_retry(payload.bind_topic, chunk_payload, payload.lite_topic, f"chunk {chunk_count}")

                # Update previous text for next comparison
                previous_text = current_text

            else:
                error_msg = f"Bailian API call failed: {chunk.message}"
                logger.error(error_msg)

                # Send error payload
                error_payload = MessagePayload(
                    trace_id=payload.trace_id,
                    role=AgentRole.WEATHER,
                    content=error_msg,
                    bind_topic=None,
                    lite_topic=None,
                    metadata={"chunk_index": chunk_count, "is_final": True, "error": True}
                )
                send_with_retry(payload.bind_topic, error_payload, payload.lite_topic, "error payload")
                return

        logger.info(f"Streaming completed - TraceID: {payload.trace_id}, Total chunks: {chunk_count}")

        # Send final marker message
        final_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.WEATHER,
            content="",
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": chunk_count, "is_final": True}
        )
        send_with_retry(payload.bind_topic, final_payload, payload.lite_topic, "final marker")

    except Exception as e:
        error_msg = f"Exception occurred while calling Bailian API: {str(e)}"
        logger.error(error_msg, exc_info=True)

        # Send exception error payload
        error_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.WEATHER,
            content=error_msg,
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": 0, "is_final": True, "error": True}
        )
        send_with_retry(payload.bind_topic, error_payload, payload.lite_topic, "exception error payload")
