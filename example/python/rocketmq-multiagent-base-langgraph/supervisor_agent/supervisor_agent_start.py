import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from common.mq_toos import logger
from supervisor_agent.utils.stream.stream_manager import stream_queue_manager
from supervisor_agent.utils.rocketmq.mq_service import init_rocketmq
from web.routes import router


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle and configure event loop for stream queue manager"""
    loop = asyncio.get_running_loop()
    stream_queue_manager.set_loop(loop)
    logger.info("Event loop configured for stream queue manager")
    yield


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
    uvicorn.run(app, host="0.0.0.0", port=8000)
