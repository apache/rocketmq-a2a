"""Configuration management for Weather Agent"""
import os
from dotenv import load_dotenv

# Load environment variables from .env file
load_dotenv()

# ==================== Application Configuration ====================

# Bailian AI application ID for weather queries
APP_ID = os.getenv("APP_ID_WEATHER")

# ==================== RocketMQ Configuration ====================

# Topic for receiving weather agent tasks
WEATHER_AGENT_TOPIC = "WeatherAgentTask"

# Consumer group ID for weather agent
CONSUMER_GROUP = "WeatherAgentTaskConsumerGroup"

# RocketMQ credentials from environment variables
ENDPOINT = os.getenv("ROCKETMQ_ENDPOINT")
ACCESS_KEY = os.getenv("ROCKETMQ_ACCESS_KEY")
SECRET_KEY = os.getenv("ROCKETMQ_SECRET_KEY")

# ==================== LLM Configuration ====================

# DashScope API key
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY")

# ==================== Default Values ====================

DEFAULT_DATE_INFO = "今天"
