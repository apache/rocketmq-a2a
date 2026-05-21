"""Configuration management for Travel Agent"""
import os
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

# ==================== Application Configuration ====================

# Bailian AI application ID for travel planning
APP_ID = os.getenv("APP_ID_TRAVEL")

# ==================== RocketMQ Configuration ====================

# Topic for receiving travel agent tasks
TRAVEL_AGENT_TOPIC = "TravelAgentTask"

# Consumer group ID for travel agent
CONSUMER_GROUP = "TravelAgentTaskConsumerGroup"

# RocketMQ credentials from environment variables
ENDPOINT = os.getenv("ROCKETMQ_ENDPOINT")
ACCESS_KEY = os.getenv("ROCKETMQ_ACCESS_KEY")
SECRET_KEY = os.getenv("ROCKETMQ_SECRET_KEY")

# ==================== LLM Configuration ====================

# DashScope API key
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY")

# ==================== Default Values ====================

DEFAULT_DATE_INFO = "近期"
DEFAULT_WEATHER_INFO = "天气信息未知"
