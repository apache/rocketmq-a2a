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

"""The command-line entry point: `python -m mcp_tasks_rocketmq`.

The tools are yours, so the entry point loads them from your code:

    --tools module:attr

where `attr` is a callable returning `(register, task_tools)`: a function that
registers the tools on an `MCPServer`, and the frozenset of names that should
run as tasks. The same spec serves a standalone worker
(`python -m mcp_tasks_rocketmq.worker --tools ...`), which is what keeps the two
registries identical.

Serving: bare argv over stdio; `--http --port N [--path /mcp]` over uvicorn on
127.0.0.1:N. `--no-worker` serves as a creating replica only, with the pool
left to standalone `worker.py` processes.
"""

from __future__ import annotations

import sys
from typing import Any

import anyio
import uvicorn
from starlette.applications import Starlette

from mcp.server.mcpserver import MCPServer
from mcp.server.transport_security import TransportSecuritySettings
from mcp_tasks_rocketmq.server import build_server, load_tools


def _run() -> None:
    if "--tools" not in sys.argv:
        raise SystemExit("usage: python -m mcp_tasks_rocketmq --tools module:attr [--no-worker] [--http]")
    register, task_tools = load_tools(_argv_after("--tools"))
    server = build_server(
        with_worker="--no-worker" not in sys.argv,
        task_tools=task_tools,
        register_tools=register,
    )
    if "--http" in sys.argv:
        port = int(_argv_after("--port", default="8000"))
        path = _argv_after("--path", default="/mcp")
        anyio.run(_serve_http, server, port, path)
    else:
        anyio.run(server.run_stdio_async)


def _argv_after(flag: str, *, default: str | None = None) -> str:
    """Return the argv token following ``flag``, or ``default`` when the flag is absent."""
    try:
        return sys.argv[sys.argv.index(flag) + 1]
    except ValueError:
        if default is None:
            raise SystemExit(f"missing required {flag}") from None
        return default


async def _serve_http(server: MCPServer[Any], port: int, path: str) -> None:
    app: Starlette = server.streamable_http_app(
        streamable_http_path=path,
        stateless_http=False,
        transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
    )
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="error")
    await uvicorn.Server(config).serve()


if __name__ == "__main__":
    _run()
