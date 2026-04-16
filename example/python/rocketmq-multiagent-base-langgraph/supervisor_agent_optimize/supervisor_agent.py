import asyncio
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from common.mq_toos import logger
from supervisor_agent_optimize.my_common.stream.stream_manager import stream_queue_manager
from supervisor_agent_optimize.rocketmq.mq_service import init_rocketmq
from supervisor_agent_optimize.web.routes import router


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Manage application lifecycle and event loop"""
    loop = asyncio.get_running_loop()
    stream_queue_manager.set_loop(loop)
    logger.info("Event loop configured for stream queue manager")
    yield


app = FastAPI(lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"]
)

app.include_router(router)


if __name__ == "__main__":
    import uvicorn

    init_rocketmq()
    logger.info("Start supervisor agent successfully")
    uvicorn.run(app, host="0.0.0.0", port=8000)





