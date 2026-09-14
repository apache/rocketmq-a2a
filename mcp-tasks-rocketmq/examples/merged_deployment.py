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

"""End-to-end MCP tasks over a real RocketMQ instance, in one process.

The merged deployment: one `build_server` call wires the creating replica, its
pool worker, and the ledger tail into one lifespan - the worker executes the
server's own tools (`worker.bind_execute(server.call_tool)` happens inside the
lifespan, invisible here). An MCP client then drives four steps, every one
printed with a timestamp.

Credentials come from the environment:

    export RMQ_ENDPOINTS=rmq-cn-xxxx.cn-hangzhou.rmq.aliyuncs.com:8080
    export RMQ_AK=... RMQ_SK=...

    pip install mcp mcp-tasks-rocketmq
    python examples/merged_deployment.py

Needs one topic and both kinds of consumer group to exist on the instance: topic
`mcp-tasks` with the LiteTopic attribute, group `mcp-tasks-workers` (plain, the
pool), and one LITE_SELECTIVE group for this replica (`mcp-tasks-tailer-a`).
"""

import logging
import os
import time
from dataclasses import replace

import anyio
from demo_tools import demo_tools
from mcp import Client
from mcp_tasks_rocketmq.client import TasksClient
from mcp_tasks_rocketmq.config import RocketMQConfig
from mcp_tasks_rocketmq.rocketmq import task_channel
from mcp_tasks_rocketmq.server import build_server
from mcp_tasks_rocketmq.wire import CreateTaskResult, GetTaskResult, TasksGetParams, TasksGetRequest

T0 = time.monotonic()

MOBY_DICK = (
    "Call me Ishmael. Some years ago - never mind how long precisely - having little or no money "
    "in my purse, and nothing particular to interest me on shore, I thought I would sail about "
    "a little and see the watery part of the world. It is a way I have of driving off the spleen "
    "and regulating the circulation. Whenever I find myself growing grim about the mouth; whenever "
    "it is a damp, drizzly November in my soul; whenever I find myself involuntarily pausing before "
    "coffin warehouses, and bringing up the rear of every funeral I meet; then, I account it high "
    "time to get to sea as soon as I can. This is my substitute for pistol and sword. There now is "
    "your insular city of the Manhattoes, belted round by wharves as Indian isles by coral reefs - "
    "commerce surrounds it with her surf. Right and left, the streets take you waterward. Its extreme "
    "downtown is the battery, where that noble mole is washed by waves, and cooled by breezes, which "
    "a few hours previous were out of sight of land. Look at the crowds of water-gazers there."
)


def log(msg: str) -> None:
    print(f"[{time.monotonic() - T0:6.3f}s] {msg}", flush=True)


async def main() -> None:
    # Read the environment, pinning the replica id so the tailer group stays
    # stable across runs.
    config = replace(RocketMQConfig.from_env(), replica_id=os.environ.get("RMQ_REPLICA_ID") or "a")
    log(f"instance {config.endpoints}")
    log(f"topic {config.tasks_topic!r} (LiteTopic): `command` to the pool, `t-<taskId>` the task's ledger")
    log(f"tailer group {config.tailer_group()!r} (LITE_SELECTIVE, this replica's own)")

    register, task_tools = demo_tools()
    mcp = build_server(config, task_tools=task_tools, register_tools=register)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        log("1) char_count - not task-eligible, answered inline, no messaging")
        counted = await client.call_tool("char_count", {"corpus": "call me ishmael"})
        log(f"   -> {counted.structured_content}")

        log("2) word_count on a long document - the replica submits it as the ledger's first record and hands back a task")
        handle = await client.session.call_tool("word_count", {"corpus": MOBY_DICK}, allow_claimed=True)
        assert isinstance(handle, CreateTaskResult), handle
        log(f"   -> resultType={handle.result_type!r} status={handle.status!r} taskId={handle.task_id}")
        log(f"      channel {task_channel(handle.task_id)!r} on {config.tasks_topic!r}, followed before the send")

        log("3) polling tasks/get until the worker's state message lands")
        polls = 0
        last_status = None
        while True:
            snapshot = await client.session.send_request(
                TasksGetRequest(params=TasksGetParams(task_id=handle.task_id)), GetTaskResult
            )
            polls += 1
            if snapshot.status != last_status:
                log(f"   -> status={snapshot.status!r}")
                last_status = snapshot.status
            if snapshot.status == "completed":
                assert snapshot.result is not None
                log(f"      result={snapshot.result['structuredContent']}   ({polls} polls)")
                break
            await anyio.sleep((handle.poll_interval_ms or 200) / 1000)
    log(f"done - {polls} polls, one `command` and one `state` message on {config.tasks_topic!r}")


if __name__ == "__main__":
    # INFO shows the plugin's own view of the messaging - what was published to
    # which channel - which is the half of a real run this script cannot narrate.
    logging.basicConfig(level=logging.INFO)
    anyio.run(main)
