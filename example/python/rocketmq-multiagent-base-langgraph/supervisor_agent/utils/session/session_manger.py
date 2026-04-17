"""Session manager for tracking active sessions"""
import threading
from typing import Set, Optional
from datetime import datetime

from common.mq_toos import logger


class SessionManager:
    """Manages active session lifecycle"""

    def __init__(self):
        self._active_sessions: Set[str] = set()
        self._session_metadata: dict = {}
        self._lock = threading.Lock()

    def add_session(self, session_id: str, metadata: Optional[dict] = None) -> None:
        """
        Add a new session or update existing session

        Args:
            session_id: The session identifier
            metadata: Optional metadata about the session (e.g., created_at, user_info)
        """
        with self._lock:
            if session_id in self._active_sessions:
                logger.info(f"Session already exists, updating: {session_id}")
            else:
                self._active_sessions.add(session_id)
                logger.info(f"New session added: {session_id}")

            # Store/update metadata
            if metadata:
                self._session_metadata[session_id] = metadata
            else:
                # Default metadata with creation timestamp
                if session_id not in self._session_metadata:
                    self._session_metadata[session_id] = {
                        "created_at": datetime.now().isoformat(),
                        "last_active": datetime.now().isoformat()
                    }
                else:
                    self._session_metadata[session_id]["last_active"] = datetime.now().isoformat()

    def remove_session(self, session_id: str) -> bool:
        """
        Remove a session

        Args:
            session_id: The session identifier to remove

        Returns:
            True if session was removed, False if it didn't exist
        """
        with self._lock:
            if session_id in self._active_sessions:
                self._active_sessions.discard(session_id)
                # Clean up metadata
                self._session_metadata.pop(session_id, None)
                logger.info(f"Session removed: {session_id}")
                return True
            else:
                logger.warning(f"Session not found for removal: {session_id}")
                return False

    def is_active(self, session_id: str) -> bool:
        """
        Check if a session is currently active

        Args:
            session_id: The session identifier

        Returns:
            True if session is active, False otherwise
        """
        with self._lock:
            return session_id in self._active_sessions

    def get_active_sessions(self) -> Set[str]:
        """
        Get all active session IDs

        Returns:
            Set of active session IDs
        """
        with self._lock:
            return self._active_sessions.copy()

    def get_session_count(self) -> int:
        """
        Get the number of active sessions

        Returns:
            Number of active sessions
        """
        with self._lock:
            return len(self._active_sessions)

    def get_session_metadata(self, session_id: str) -> Optional[dict]:
        """
        Get metadata for a specific session

        Args:
            session_id: The session identifier

        Returns:
            Session metadata dict or None if session doesn't exist
        """
        with self._lock:
            return self._session_metadata.get(session_id)

    def clear_all_sessions(self) -> None:
        """Clear all active sessions"""
        with self._lock:
            count = len(self._active_sessions)
            self._active_sessions.clear()
            self._session_metadata.clear()
            logger.info(f"All sessions cleared: {count} sessions removed")


# Global session manager instance
session_manager = SessionManager()
