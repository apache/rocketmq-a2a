"""Weather Agent application entry point"""
import logging
import signal
import threading

from weather_agent.handler.message_handler import handle_message
from weather_agent.rocketmq.mq_service import init_rocketmq, shutdown

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


def main():
    """Main entry point for Weather Agent application."""
    logger.info("Starting Weather Agent...")

    shutdown_event = threading.Event()

    def signal_handler(signum, frame):
        """Handle termination signals for graceful shutdown."""
        logger.info(f"Received signal {signum}")
        shutdown_event.set()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    try:
        # Initialize RocketMQ clients with message handler
        init_rocketmq(handle_message)
        logger.info("Weather Agent started successfully")

        # Wait for shutdown signal
        shutdown_event.wait()

    except Exception as e:
        logger.error(f"Fatal error: {e}", exc_info=True)
    finally:
        shutdown()
        logger.info("Weather Agent stopped")


if __name__ == "__main__":
    main()
