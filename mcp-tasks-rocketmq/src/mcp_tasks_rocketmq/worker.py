# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""An extra worker process, for scaling execution past the serving one.

`server.py` already runs a worker inside the server, so this is not the
other half of a pair - it is one more consumer in the same group
(`RMQ_WORKER_GROUP`), competing for the same `command` messages. Start as many as
the load needs; each submission still runs on exactly one of them, and the state
it publishes still goes to the task's own channel, so nothing about the mechanism
changes with the count.

What it does not share with the serving process is the tool registry: there is no
connection here, so tools come from an `MCPServer` used purely as one. That is
the cost of scaling out - the register function has to keep this registry and the
server's in step - and the reason merged is the default.

Run it (see the README for the environment variables):

    RMQ_ENDPOINTS=... uv run python -m mcp_tasks_rocketmq.worker --tools module:attr
"""

from __future__ import annotations

import logging
import sys
from collections.abc import Callable
from typing import Any

import anyio

from mcp.server.mcpserver import MCPServer
from mcp_tasks_rocketmq.config import RocketMQConfig
from mcp_tasks_rocketmq.rocketmq import RocketMQTaskWorker
from mcp_tasks_rocketmq.server import build_worker, load_tools

logger = logging.getLogger(__name__)


def build_standalone_worker(
    config: RocketMQConfig | None = None,
    *,
    register_tools: Callable[[MCPServer[Any]], None],
) -> RocketMQTaskWorker:
    """A worker whose tool dispatch is a registry of its own, since it serves nobody.

    `register_tools` must be the same function the server was built with, or the
    tools this registry lists and the tools submitted work names will drift.
    """
    tools: MCPServer[dict[str, Any]] = MCPServer[dict[str, Any]]("tasks-rocketmq-worker")
    register_tools(tools)
    worker = build_worker(config if config is not None else RocketMQConfig.from_env())
    worker.bind_execute(tools.call_tool)
    return worker


async def main() -> None:
    """Serve until interrupted."""
    if "--tools" not in sys.argv:
        raise SystemExit("usage: python -m mcp_tasks_rocketmq.worker --tools module:attr")
    register, _ = load_tools(sys.argv[sys.argv.index("--tools") + 1])
    await build_standalone_worker(register_tools=register).serve()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    try:
        anyio.run(main)
    except KeyboardInterrupt:
        logger.info("worker stopped")
