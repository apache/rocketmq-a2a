"""Message handler for processing travel task requests"""
import json
import logging

from common.models import MessagePayload, AgentRole
from travel_agent.bailian.bailian_service import generate_travel_itinerary_streaming
from travel_agent.config.config import DEFAULT_DATE_INFO, DEFAULT_WEATHER_INFO
from travel_agent.rocketmq.mq_service import send_message

logger = logging.getLogger(__name__)


def handle_message(payload: MessagePayload) -> None:
    """
    Process travel task message and send back the result using streaming.

    Extracts request, date, and weather_info from payload, calls Bailian AI to generate
    a weather-aware travel itinerary with streaming output.

    Args:
        payload: Message payload containing travel task details with weather information
    """
    try:
        # Parse message content
        data = json.loads(payload.content)
        request = data.get("request", "")
        date_info = data.get("date", DEFAULT_DATE_INFO)
        weather_info = data.get("weather_info", DEFAULT_WEATHER_INFO)

        logger.info(f"Processing travel request - TraceID: {payload.trace_id}")
        logger.info(f"Date: {date_info}, Weather info length: {len(weather_info)} chars")

        # Generate weather-aware travel itinerary using Bailian AI with streaming
        generate_travel_itinerary_streaming(request, date_info, weather_info, payload)

        logger.info(f"Travel itinerary streaming completed for trace_id: {payload.trace_id}")

    except Exception as e:
        logger.error(f"Error processing travel request: {e}", exc_info=True)

        # Send error response
        error_payload = MessagePayload(
            trace_id=payload.trace_id,
            role=AgentRole.TRAVEL,
            content=f"Processing failed: {str(e)}",
            bind_topic=None,
            lite_topic=None,
            metadata={"chunk_index": 0, "is_final": True, "error": True}
        )
        send_message(payload.bind_topic, error_payload, payload.lite_topic)
