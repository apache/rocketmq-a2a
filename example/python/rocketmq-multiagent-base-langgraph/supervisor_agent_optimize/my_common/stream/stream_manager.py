"""Stream queue manager for SSE streaming"""
import threading
from asyncio import Queue
from collections import defaultdict
from typing import Optional

from common.models import MessagePayload
from common.mq_toos import logger


class StreamQueueManager:
    """Manages async queues for streaming RocketMQ messages to SSE clients"""

    def __init__(self):
        self.queues = defaultdict(list)
        self.sub_to_main_map = {}
        self.lock = threading.Lock()
        self.loop = None
        self.chat_responses = {}

    def set_loop(self, loop):
        """Set event loop for async operations"""
        self.loop = loop

    def register_trace(self, trace_id: str) -> Queue:
        """Register a new queue for a trace_id"""
        queue = Queue()
        with self.lock:
            self.queues[trace_id].append(queue)
        logger.info(f"Registered queue for trace_id: {trace_id}")
        return queue

    def register_sub_trace(self, sub_trace_id: str, main_trace_id: str):
        """Map a sub-trace_id to its parent main_trace_id"""
        with self.lock:
            self.sub_to_main_map[sub_trace_id] = main_trace_id
        logger.debug(f"Mapped sub_trace {sub_trace_id} -> main_trace {main_trace_id}")

    def store_chat_response(self, trace_id: str, response: str):
        """Store chat response for later retrieval"""
        with self.lock:
            self.chat_responses[trace_id] = response
        logger.info(f"Stored chat response for trace_id: {trace_id}")

    def get_chat_response(self, trace_id: str) -> Optional[str]:
        """Retrieve and remove chat response"""
        with self.lock:
            return self.chat_responses.pop(trace_id, None)

    async def put_payload(self, payload: MessagePayload):
        """Put payload into all registered queues for this trace_id or its parent"""
        trace_id = payload.trace_id

        with self.lock:
            queues = self.queues.get(trace_id, [])

            if not queues and trace_id in self.sub_to_main_map:
                main_trace_id = self.sub_to_main_map[trace_id]
                queues = self.queues.get(main_trace_id, [])
                logger.debug(f"Routed sub-trace {trace_id} to main_trace {main_trace_id}")

        if queues:
            for queue in queues:
                await queue.put(payload)
                logger.debug(
                    f"Payload sent to queue for trace_id: {trace_id}, chunk_index: {payload.metadata.get('chunk_index', 'N/A')}")
        else:
            logger.warning(f"No queue found for trace_id: {trace_id}")

    def unregister_trace(self, trace_id: str, queue: Queue):
        """Remove a queue from the trace_id registration"""
        with self.lock:
            if trace_id in self.queues:
                if queue in self.queues[trace_id]:
                    self.queues[trace_id].remove(queue)
                if not self.queues[trace_id]:
                    del self.queues[trace_id]

            keys_to_remove = [k for k, v in self.sub_to_main_map.items() if v == trace_id]
            for key in keys_to_remove:
                del self.sub_to_main_map[key]

            self.chat_responses.pop(trace_id, None)

        logger.info(f"Unregistered queue for trace_id: {trace_id}")


stream_queue_manager = StreamQueueManager()
