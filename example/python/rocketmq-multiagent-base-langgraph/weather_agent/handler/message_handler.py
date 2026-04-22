"""Message handler for processing weather task requests"""
import json
import logging

from common.models import MessagePayload, AgentRole
from weather_agent.bailian.bailian_service import query_weather_streaming
from weather_agent.config.config import DEFAULT_DATE_INFO
from weather_agent.rocketmq.mq_service import send_message

logger = logging.getLogger(__name__)


def handle_message(payload: MessagePayload) -> None:
    """
    Process weather task message and send back the result using streaming.

    Extracts city and date from payload, calls Bailian AI to get weather info
    with streaming output.

    Args:
        payload: Message payload containing weather task details
    """
    try:
        # Parse message content
        data = json.loads(payload.content)
        city = data.get("city", "")
        date_info = data.get("date", DEFAULT_DATE_INFO)

        logger.info(f"Processing weather request - TraceID: {payload.trace_id}")
        logger.info(f"City: {city}, Date: {date_info}")

        # Query weather using Bailian AI with streaming
        query_weather_streaming(city, date_info, payload)

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
