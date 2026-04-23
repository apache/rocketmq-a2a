"""Session manager for tracking active sessions and lifecycle"""
import threading
import time
from typing import Set, Optional, Dict, Any
from enum import Enum

from common.rocketmq.rocketmq_utils import logger


class SessionStatus(str, Enum):
    """Session status enumeration"""
    ACTIVE = "active"
    DISCONNECTED = "disconnected"
    COMPLETED = "completed"


class SessionManager:
    """Manages active session lifecycle with metadata tracking"""

    def __init__(self, session_timeout: int = 3600):
        """
        Initialize session manager.

        Args:
            session_timeout: Session timeout in seconds (default: 1 hour)
        """
        self._active_sessions: Set[str] = set()
        self._session_metadata: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._session_timeout = session_timeout

    def add_session(self, session_id: str, metadata: Optional[Dict[str, Any]] = None) -> None:
        """
        Add a new session or update existing session metadata.

        Args:
            session_id: Unique session identifier
            metadata: Optional session metadata (e.g., trace_id, user_input)
        """
        with self._lock:
            is_new_session = session_id not in self._active_sessions

            if is_new_session:
                self._active_sessions.add(session_id)
                logger.info(f"New session added: {session_id}")
            else:
                logger.debug(f"Session updated: {session_id}")

            # Initialize or update metadata
            current_time = time.time()
            if metadata:
                # Merge provided metadata with timestamps
                self._session_metadata[session_id] = {
                    **metadata,
                    "last_active": current_time
                }
            else:
                # Create default metadata for new sessions
                if is_new_session:
                    self._session_metadata[session_id] = {
                        "created_at": current_time,
                        "last_active": current_time,
                        "status": SessionStatus.ACTIVE.value
                    }
                else:
                    # Update last active timestamp for existing session
                    self._session_metadata[session_id]["last_active"] = current_time

    def remove_session(self, session_id: str) -> bool:
        """
        Remove a session completely from tracking.

        Args:
            session_id: Session identifier to remove

        Returns:
            True if session was removed, False if it didn't exist
        """
        with self._lock:
            if session_id in self._active_sessions:
                self._active_sessions.discard(session_id)
                self._session_metadata.pop(session_id, None)
                logger.info(f"Session removed: {session_id}")
                return True
            else:
                logger.warning(f"Session not found for removal: {session_id}")
                return False

    def is_active(self, session_id: str) -> bool:
        """
        Check if a session is currently active.

        Args:
            session_id: Session identifier to check

        Returns:
            True if session is active
        """
        with self._lock:
            return session_id in self._active_sessions

    def get_active_sessions(self) -> Set[str]:
        """
        Get all active session IDs.

        Returns:
            Copy of active session IDs set
        """
        with self._lock:
            return self._active_sessions.copy()

    def get_session_count(self) -> int:
        """
        Get the number of active sessions.

        Returns:
            Count of active sessions
        """
        with self._lock:
            return len(self._active_sessions)

    def get_session_metadata(self, session_id: str) -> Optional[Dict[str, Any]]:
        """
        Get metadata for a specific session.

        Args:
            session_id: Session identifier

        Returns:
            Session metadata dict or None if not found
        """
        with self._lock:
            return self._session_metadata.get(session_id)

    def update_session_status(self, session_id: str, status: SessionStatus) -> None:
        """
        Update session status.

        Args:
            session_id: Session identifier
            status: New session status
        """
        with self._lock:
            if session_id in self._session_metadata:
                self._session_metadata[session_id]["status"] = status.value
                self._session_metadata[session_id]["last_active"] = time.time()
                logger.debug(f"Session {session_id} status updated to: {status.value}")

    def cleanup_expired_sessions(self) -> int:
        """
        Remove sessions that have been disconnected beyond timeout period.

        Returns:
            Number of sessions cleaned up
        """
        current_time = time.time()
        expired_sessions = []

        with self._lock:
            for session_id, metadata in self._session_metadata.items():
                disconnected_at = metadata.get("disconnected_at")

                # Check if session is disconnected and exceeded timeout
                if disconnected_at and (current_time - disconnected_at) > self._session_timeout:
                    expired_sessions.append(session_id)

            # Remove expired sessions
            for session_id in expired_sessions:
                self._active_sessions.discard(session_id)
                del self._session_metadata[session_id]
                logger.info(f"Cleaned up expired session: {session_id}")

        return len(expired_sessions)

    def clear_all_sessions(self) -> None:
        """Clear all active sessions and metadata"""
        with self._lock:
            count = len(self._active_sessions)
            self._active_sessions.clear()
            self._session_metadata.clear()
            logger.info(f"All sessions cleared: {count} sessions removed")


# Global session manager instance with 1-hour timeout
session_manager = SessionManager(session_timeout=3600)

