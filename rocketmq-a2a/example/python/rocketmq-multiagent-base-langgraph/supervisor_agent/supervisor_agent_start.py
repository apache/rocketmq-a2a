import asyncio
import threading
import time
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from common.rocketmq.rocketmq_utils import logger
from supervisor_agent.utils.session.session_manager import session_manager
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.rocketmq.mq_service import init_rocketmq
from supervisor_agent.api.routes import router


def session_cleanup_worker(interval: int = 300):
    """Background thread to periodically clean up expired sessions"""
    logger.info(f"Session cleanup worker started (interval: {interval}s)")

    while True:
        try:
            time.sleep(interval)
            cleaned_count = session_manager.cleanup_expired_sessions()
            if cleaned_count > 0:
                logger.info(f"Cleaned up {cleaned_count} expired sessions")
        except Exception as e:
            logger.error(f"Session cleanup error: {e}", exc_info=True)



@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle and configure event loop for stream queue manager"""
    # Set event loop for stream queue manager
    loop = asyncio.get_running_loop()
    stream_queue_manager.set_loop(loop)
    logger.info("Event loop configured for stream queue manager")

    # Start background session cleanup thread
    cleanup_thread = threading.Thread(
        target=session_cleanup_worker,
        kwargs={"interval": 300},  # Check every 5 minutes
        daemon=True  # Thread will exit when main process exits
    )
    cleanup_thread.start()
    logger.info("Session cleanup background thread started")

    yield

    # Cleanup on shutdown (optional)
    logger.info("Application shutting down...")


app = FastAPI(lifespan=lifespan)

# Configure CORS middleware to allow all origins, methods, and headers
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"]
)

# Include API routes from router module
app.include_router(router)


if __name__ == "__main__":
    import uvicorn

    # Initialize RocketMQ consumer and producer clients
    init_rocketmq()
    logger.info("Start supervisor agent successfully")

    # Start FastAPI server with uvicorn
    uvicorn.run(app, host="localhost", port=8000)
