"""VoxHarness backend entry point."""

import asyncio
import logging
import sys

from config import Config
from server import Server


def setup_logging():
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        stream=sys.stderr,
    )


def main():
    setup_logging()
    config = Config.from_env()
    server = Server(config)
    asyncio.run(server.start())


if __name__ == "__main__":
    main()
