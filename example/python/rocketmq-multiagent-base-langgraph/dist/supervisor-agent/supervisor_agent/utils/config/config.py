"""Configuration management for Supervisor Agent"""
import os
import uuid
from typing import Optional

from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()


# ==================== RocketMQ Configuration ====================

# RocketMQ service endpoint
ROCKETMQ_ENDPOINT: str = os.getenv("ROCKETMQ_ENDPOINT", "")

# RocketMQ authentication credentials (optional)
ROCKETMQ_ACCESS_KEY: Optional[str] = os.getenv("ROCKETMQ_ACCESS_KEY")
ROCKETMQ_SECRET_KEY: Optional[str] = os.getenv("ROCKETMQ_SECRET_KEY")


# ==================== RocketMQ Topics and Groups ====================

# Topic for weather agent tasks
WEATHER_AGENT_TOPIC: str = "WeatherAgentTask"

# Topic for travel agent tasks
TRAVEL_AGENT_TOPIC: str = "TravelAgentTask"

# Consumer group ID for worker agent responses
WORK_AGENT_RESPONSE_GROUP_ID: str = "CID_HOST_AGENT_LITE"

# Topic for worker agent responses
WORK_AGENT_RESPONSE_TOPIC: str = "WorkerAgentResponse"


# ==================== LLM Configuration ====================

# DashScope API key for LLM services
DASHSCOPE_API_KEY: Optional[str] = os.getenv("DASHSCOPE_API_KEY")


# ==================== Session Configuration ====================

# Global default session ID (generated once at startup)
SESSION_ID: str = str(uuid.uuid4())

