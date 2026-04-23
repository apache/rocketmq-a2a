"""Common shared module for RocketMQ Multi-Agent system.

This module provides shared utilities, models, and configurations
used by all agent modules.
"""


__version__ = "0.1.0"
__author__ = "RocketMQ Multi-Agent Contributors"

__all__ = [
    "AgentRole",
    "MessagePayload",
    "logger",
]

from common.model.models import AgentRole, MessagePayload
from common.rocketmq.rocketmq_utils import logger
