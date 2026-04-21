
import json
from enum import Enum
from typing import Dict
from typing import Optional


class AgentRole(str, Enum):
    WEATHER = "weather"
    TRAVEL = "travel"
    ASSISTANT = "assistant"

class MessagePayload:
    def __init__(self, trace_id: str, role: AgentRole, content: str, bind_topic: Optional[str], lite_topic: Optional[str], metadata: Dict = None, offset: Optional[int] = None):
        self.trace_id = trace_id
        self.role = role
        self.content = content
        self.bind_topic = bind_topic
        self.lite_topic = lite_topic
        self.metadata = metadata or {}
        self.offset = offset

    def to_json(self) -> str:
        return json.dumps({
            "trace_id": self.trace_id,
            "role": self.role.value,
            "content": self.content,
            "bind_topic": self.bind_topic,
            "lite_topic": self.lite_topic,
            "metadata": self.metadata,
            "offset": self.offset
        })

    @staticmethod
    def from_json(json_str: str) -> 'MessagePayload':
        data = json.loads(json_str)
        return MessagePayload(
            trace_id=data["trace_id"],
            role=AgentRole(data["role"]),
            content=data["content"],
            bind_topic=data["bind_topic"],
            lite_topic=data["lite_topic"],
            metadata=data.get("metadata", {}),
            offset=data.get("offset")
        )
