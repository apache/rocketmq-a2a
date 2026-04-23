"""Bailian AI service for travel itinerary generation"""
import logging
import time

import dashscope

from common.model.models import MessagePayload, AgentRole
from travel_agent.config.config import DASHSCOPE_API_KEY, APP_ID
from travel_agent.rocketmq.mq_service import send_message

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


def generate_travel_itinerary_streaming(request: str, date_info: str, weather_info: str,
                                        payload: MessagePayload) -> None:
    """
    Call Bailian AI to generate travel itinerary with streaming output.
    Creates weather-aware travel plans with incremental chunk delivery.

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
                        role=AgentRole.TRAVEL,
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
                    role=AgentRole.TRAVEL,
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
            role=AgentRole.TRAVEL,
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
            role=AgentRole.TRAVEL,
            content=error_msg,
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": 0, "is_final": True, "error": True}
        )
        send_with_retry(payload.bind_topic, error_payload, payload.lite_topic, "exception error payload")
