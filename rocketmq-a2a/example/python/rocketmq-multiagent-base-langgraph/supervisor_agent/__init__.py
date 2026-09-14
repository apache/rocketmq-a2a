"""Supervisor Agent - Central orchestrator for multi-agent system.

The Supervisor Agent coordinates task distribution among specialized
agents (Travel Agent, Weather Agent) and manages client sessions
with real-time streaming responses via Server-Sent Events (SSE).

Key Features:
    - Session management and tracking
    - Task routing to appropriate agents
    - Real-time response streaming
    - RocketMQ message coordination
"""

__version__ = "0.1.0"
__author__ = "RocketMQ Multi-Agent Contributors"

# Optional: Expose main entry point
__all__ = []
