"""Stream queue manager for SSE streaming"""
import asyncio
import threading
from asyncio import Queue
from collections import defaultdict
from typing import Optional, List, Dict

from common.model.models import MessagePayload
from common.rocketmq.rocketmq_utils import logger


class StreamQueueManager:
    """Manages async queues for streaming RocketMQ messages to SSE clients"""

    def __init__(self):
        # Queue storage: trace_id -> list of Queue instances
        self.queues: Dict[str, List[Queue]] = defaultdict(list)
        # Sub-trace to main-trace mapping: sub_trace_id -> main_trace_id
        self.sub_to_main_map: Dict[str, str] = {}
        # Thread lock for concurrent access protection
        self.lock = threading.Lock()
        # Event loop reference for cross-thread coroutine scheduling
        self.loop: Optional[asyncio.AbstractEventLoop] = None
        # Chat response cache: trace_id -> complete response string
        self.chat_responses: Dict[str, str] = {}

    def set_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        """
        Set event loop for async operations.

        Args:
            loop: Asyncio event loop instance
        """
        self.loop = loop
        logger.info("Event loop registered for stream queue manager")

    def register_trace(self, trace_id: str) -> Queue:
        """
        Register a new async queue for a given trace_id.

        Args:
            trace_id: Unique trace identifier

        Returns:
            Newly created async queue
        """
        queue = Queue()
        with self.lock:
            self.queues[trace_id].append(queue)
        logger.debug(f"Registered queue for trace_id: {trace_id}")
        return queue

    def register_sub_trace(self, sub_trace_id: str, main_trace_id: str) -> None:
        """
        Map a sub-trace_id to its parent main_trace_id for message routing.

        Args:
            sub_trace_id: Sub-trace identifier (e.g., weather_trace, travel_trace)
            main_trace_id: Parent main trace identifier
        """
        with self.lock:
            self.sub_to_main_map[sub_trace_id] = main_trace_id
        logger.debug(f"Mapped sub_trace {sub_trace_id} -> main_trace {main_trace_id}")

    def store_chat_response(self, trace_id: str, response: str) -> None:
        """
        Store complete chat response for later retrieval.

        Args:
            trace_id: Trace identifier
            response: Complete chat response text
        """
        with self.lock:
            self.chat_responses[trace_id] = response
        logger.debug(f"Stored chat response for trace_id: {trace_id}")

    def get_chat_response(self, trace_id: str) -> Optional[str]:
        """
        Retrieve and remove cached chat response by trace_id.

        Args:
            trace_id: Trace identifier

        Returns:
            Cached response string or None if not found
        """
        with self.lock:
            return self.chat_responses.pop(trace_id, None)

    async def put_payload(self, payload: MessagePayload) -> None:
        """
        Put payload into registered queues, routing sub-traces to main trace if needed.

        Args:
            payload: Message payload to distribute
        """
        trace_id = payload.trace_id

        with self.lock:
            # Try to find queues directly by trace_id
            queues = self.queues.get(trace_id, [])

            # If not found, check if it's a sub-trace and route to main trace
            if not queues and trace_id in self.sub_to_main_map:
                main_trace_id = self.sub_to_main_map[trace_id]
                queues = self.queues.get(main_trace_id, [])
                logger.debug(f"Routed sub-trace {trace_id} to main_trace {main_trace_id}")

        # Deliver payload to all matching queues
        if queues:
            for queue in queues:
                await queue.put(payload)
                logger.debug(
                    f"Payload sent - TraceID: {trace_id}, "
                    f"ChunkIndex: {payload.metadata.get('chunk_index', 'N/A')}"
                )
        else:
            logger.warning(f"No queue found for trace_id: {trace_id}")

    def unregister_trace(self, trace_id: str, queue: Queue) -> None:
        """
        Remove a queue from trace registration and clean up related mappings.

        Args:
            trace_id: Trace identifier to clean up
            queue: Specific queue instance to remove
        """
        with self.lock:
            # Remove queue from trace_id's queue list
            if trace_id in self.queues:
                if queue in self.queues[trace_id]:
                    self.queues[trace_id].remove(queue)

                # Delete trace_id entry if no queues remain
                if not self.queues[trace_id]:
                    del self.queues[trace_id]

            # Clean up sub-trace mappings pointing to this main trace
            keys_to_remove = [
                k for k, v in self.sub_to_main_map.items()
                if v == trace_id
            ]
            for key in keys_to_remove:
                del self.sub_to_main_map[key]

            # Remove cached chat response
            self.chat_responses.pop(trace_id, None)

        logger.debug(f"Unregistered queue for trace_id: {trace_id}")


# Global stream queue manager instance
stream_queue_manager = StreamQueueManager()

