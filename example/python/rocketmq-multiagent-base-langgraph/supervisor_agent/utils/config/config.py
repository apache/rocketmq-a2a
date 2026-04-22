"""Configuration management for Supervisor Agent"""
import os
import uuid
from dotenv import load_dotenv

load_dotenv()

# RocketMQ Configuration
ROCKETMQ_ENDPOINT = os.getenv("ROCKETMQ_ENDPOINT")
ROCKETMQ_ACCESS_KEY = os.getenv("ROCKETMQ_ACCESS_KEY")
ROCKETMQ_SECRET_KEY = os.getenv("ROCKETMQ_SECRET_KEY")

# Topics
WEATHER_AGENT_TOPIC = "WeatherAgentTask"
TRAVEL_AGENT_TOPIC = "TravelAgentTask"
WORK_AGENT_RESPONSE_GROUP_ID = "CID_HOST_AGENT_LITE"
WORK_AGENT_RESPONSE_TOPIC = "WorkerAgentResponse"

# LLM Configuration
DASHSCOPE_API_KEY = os.getenv("DASHSCOPE_API_KEY")

# Global Default Session
SESSION_ID = str(uuid.uuid4())
