"""Data models for Agent workflow"""
from typing import TypedDict, Optional


class AgentState(TypedDict):
    """Agent state definition for LangGraph workflow"""
    trace_id: str  # Unique identifier for tracking the request flow
    session_id: str  # Session identifier for RocketMQ subscription and multi-session support
    user_input: str  # Original user query or message
    intent: str  # Detected user intent (e.g., "travel", "weather", "chat")
    city: str  # Target city extracted from user input
    date_info: str  # Travel or query date information
    weather_data: str  # Weather information retrieved from weather agent
    final_response: str  # Final aggregated response to send to user
    weather_trace_id: Optional[str]  # Trace ID for async weather agent task
    travel_trace_id: Optional[str]  # Trace ID for async travel agent task
    weather_complete: bool  # Flag indicating if weather data collection is complete
