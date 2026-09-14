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

"""The deployment wiring: RocketMQ clients, groups, and the `Tasks` extension.

Identical to a plain tasks server from the client's side - same tools, same
`tasks/get`, same `resultType: "task"` handle - except the extension is
constructed with a `RocketMQTaskDispatcher`, so `summarize` runs off the back of
a submit message rather than in the request that asked for it. The worker that
picks that message up lives here too, which is the default deployment: one
build, one process, one lifespan (`MCPServer(lifespan=backend.runtime)`).
`mcp_tasks_rocketmq.worker` starts more of the same worker when execution needs
to scale past this process.

This file is the deployment's wiring - the clients, and the two groups this
process joins - and nothing about the mechanism, which lives in `rocketmq.py`.
The groups are where the deployment's shape shows: one shared group for the
pool, because submitted work is a queue, and one group per replica for the
ledger tail, because a task's records must reach every replica that follows it
and a lite offset is per (group, channel).

Serve it (see the README for the environment variables):

    RMQ_ENDPOINTS=... uv run python -m mcp_tasks_rocketmq --http
"""

# pyright: reportMissingImports=false, reportUnknownVariableType=false, reportUnknownMemberType=false
# The `rocketmq` client is an optional runtime dependency, imported lazily below.

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp_tasks_rocketmq.config import RocketMQConfig
from mcp_tasks_rocketmq.rocketmq import (
    TAG_COMMAND,
    RocketMQTaskBackend,
    RocketMQTaskDispatcher,
    RocketMQTaskTailer,
    RocketMQTaskWorker,
)


def build_tailer(config: RocketMQConfig) -> RocketMQTaskTailer:
    """Wire this replica's ledger tail to a `LitePushConsumer` on a group of its own.

    The consumer is bound to the parent topic and takes the tailer as its listener;
    channels are subscribed one per task, by `dispatch` before it submits and by a
    store-missing `tasks/get`. This is the deployment's whole read path besides its
    own store: no `Mcp-Name` affinity is required for a replica to answer a task it
    never created.
    """
    from rocketmq import LitePushConsumer

    tailer = RocketMQTaskTailer(topic=config.tasks_topic)
    tailer.attach_consumer(LitePushConsumer(config.client_configuration(), config.tailer_group(), config.tasks_topic, tailer))
    return tailer


def build_dispatcher(config: RocketMQConfig, tailer: RocketMQTaskTailer) -> RocketMQTaskDispatcher:
    """Wire a dispatcher to a producer for the parent topic, following channels through `tailer`.

    The same tailer the reads go through, because creating a task and reading one
    are not two roles: the submit is the ledger's first record, and the replica that
    wrote it follows the channel it wrote to.
    """
    from rocketmq import Producer

    return RocketMQTaskDispatcher(
        Producer(config.client_configuration(), (config.tasks_topic,)),
        tailer=tailer,
        topic=config.tasks_topic,
    )


def build_worker(config: RocketMQConfig) -> RocketMQTaskWorker:
    """Wire a worker to RocketMQ clients built from `config`.

    The consumer group is shared by the whole pool: submitted work is a queue,
    not a broadcast, so exactly one worker must get each task - whether it runs
    here or in a `worker.py` process. Its subscription is a plain one on the
    parent topic, which is what brings retries, the dead-letter topic and backlog
    alarms along, filtered to the `command` TAG so the pool never receives the
    state messages it publishes itself.

    No `execute`: whoever hosts the worker binds the tool dispatch it should run
    submitted work through.
    """
    from rocketmq import FilterExpression, Producer, PushConsumer

    worker = RocketMQTaskWorker(Producer(config.client_configuration(), (config.tasks_topic,)), topic=config.tasks_topic)
    worker.attach_consumer(
        PushConsumer(
            config.client_configuration(),
            config.worker_group,
            worker,
            {config.tasks_topic: FilterExpression(TAG_COMMAND)},
        )
    )
    return worker


def build_server(
    config: RocketMQConfig | None = None,
    *,
    with_worker: bool = True,
    task_tools: frozenset[str],
    register_tools: Callable[[MCPServer[Any]], None],
    name: str = "mcp-tasks-rocketmq",
) -> MCPServer[dict[str, Any]]:
    """Build the wired server: dispatcher, tailer, the `Tasks` extension, and a worker.

    The worker runs in this process by default (the merged deployment). Pass
    `with_worker=False` to run a creating replica only; the pool then lives in
    `worker.py` processes sharing the same consumer group.

    `register_tools` and `task_tools` are required: the first is called once
    with the server to register the embedder's tools, the second names which of
    them run as tasks. A standalone worker must be given the same
    `register_tools` (see `worker.build_standalone_worker`), or the two
    registries drift.

    With no `config`, reads `RocketMQConfig.from_env()`; embedders pass their own.
    `name` is the served server's name, which the client sees on initialize.
    """
    if config is None:
        config = RocketMQConfig.from_env()
    tailer = build_tailer(config)
    worker = build_worker(config) if with_worker else None
    backend = RocketMQTaskBackend(
        build_dispatcher(config, tailer), worker, tailer=tailer, task_tools=task_tools
    )
    mcp = MCPServer[dict[str, Any]](name, lifespan=backend.runtime, extensions=[backend.extension])
    # The pool's registry is this server's own: the backend runs submitted work
    # through `mcp.call_tool`, so listing and executing cannot disagree.
    register_tools(mcp)
    return mcp


def load_tools(spec: str) -> tuple[Callable[[MCPServer[Any]], None], frozenset[str]]:
    """Import `module:attr` and call it; it returns `(register, task_tools)`.

    The entry points take `--tools module:attr` so a deployment's tools live in
    the deployment's own code. The same spec must serve the server and every
    standalone worker, which is what keeps their registries identical.
    """
    import importlib

    try:
        module_name, attr = spec.split(":", 1)
        factory = getattr(importlib.import_module(module_name), attr)
        register, task_tools = factory()
    except Exception as exc:  # a bad spec is a startup error, reported as one
        raise SystemExit(f"cannot load tools from {spec!r}: {exc}") from exc
    return register, frozenset(task_tools)
