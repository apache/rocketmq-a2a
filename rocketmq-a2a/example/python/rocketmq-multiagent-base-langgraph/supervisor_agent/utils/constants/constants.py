"""Workflow and session metadata constants"""

# ==================== Node Names ====================
NODE_ROUTER = "router"
NODE_WEATHER = "weather_node"
NODE_TRAVEL = "travel_node"
NODE_CHAT = "chat_node"

# ==================== LLM Configuration ====================
LLM_MODEL_NAME = "qwen-turbo"

# ==================== Intent Values ====================
INTENT = "intent"
WEATHER_INTENT = "weather"
TRAVEL_INTENT = "travel"

# ==================== Trace ID Prefixes ====================
TRACE_PREFIX_MAIN = "main_"
TRACE_PREFIX_WEATHER = "weather_"
TRACE_PREFIX_TRAVEL = "travel_"

# ==================== Session Metadata Keys ====================
SESSION_KEY_TRACE_ID = "trace_id"
SESSION_KEY_USER_INPUT = "user_input"
SESSION_KEY_CREATED_AT = "created_at"
SESSION_KEY_LAST_ACTIVE = "last_active"
SESSION_KEY_STATUS = "status"
SESSION_KEY_DISCONNECTED_AT = "disconnected_at"
SESSION_KEY_WEATHER_TRACE_ID = "weather_trace_id"
SESSION_KEY_TRAVEL_TRACE_ID = "travel_trace_id"
SESSION_KEY_INTENT = "intent"

# ==================== Session Status Values ====================
SESSION_STATUS_ACTIVE = "active"
SESSION_STATUS_DISCONNECTED = "disconnected"
SESSION_STATUS_COMPLETED = "completed"

# ==================== AgentState Keys ====================
STATE_KEY_TRACE_ID = "trace_id"
STATE_KEY_SESSION_ID = "session_id"
STATE_KEY_USER_INPUT = "user_input"
STATE_KEY_INTENT = "intent"
STATE_KEY_CITY = "city"
STATE_KEY_DATE_INFO = "date_info"
STATE_KEY_WEATHER_DATA = "weather_data"
STATE_KEY_FINAL_RESPONSE = "final_response"
STATE_KEY_WEATHER_TRACE_ID = "weather_trace_id"
STATE_KEY_TRAVEL_TRACE_ID = "travel_trace_id"
STATE_KEY_WEATHER_COMPLETE = "weather_complete"

# ==================== Message Metadata Keys ====================
MSG_METADATA_IS_FINAL = "is_final"
MSG_METADATA_ERROR = "error"
MSG_METADATA_CHUNK_INDEX = "chunk_index"

# ==================== SSE Event Types ====================
SSE_EVENT_TYPE_START = "start"
SSE_EVENT_TYPE_CHUNK = "chunk"
SSE_EVENT_TYPE_ERROR = "error"
SSE_EVENT_TYPE_RECONNECTED = "reconnected"
SSE_EVENT_DONE = "[DONE]"
