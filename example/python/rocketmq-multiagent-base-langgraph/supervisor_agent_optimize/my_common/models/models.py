"""Data models for Agent workflow"""
from typing import TypedDict, Optional


class AgentState(TypedDict):
    """Agent state definition for LangGraph workflow"""
    trace_id: str
    user_input: str
    intent: str
    city: str
    date_info: str
    weather_data: str
    final_response: str
    weather_trace_id: Optional[str]
    travel_trace_id: Optional[str]
    weather_complete: bool
