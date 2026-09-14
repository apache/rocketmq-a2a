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

"""The whole task lifecycle with no broker: an in-memory stand-in for the client.

Every other example needs a RocketMQ instance. This one runs the real plugin -
`RocketMQTaskDispatcher`, `RocketMQTaskWorker`, `RocketMQTaskTailer`, the `Tasks`
extension - against a `Loopback` broker that reproduces the one mechanism the
topology rests on: a lite message is indexed twice, so it reaches both a plain
subscription on the parent topic (subject to that subscription's TAG filter) and
whoever holds its channel. Messages are the client library's own `rocketmq.Message`;
only the network is faked.

Delivery is explicit (`broker.deliver()`), which is what makes the walkthrough
readable: a task is observably `working` before the pool is allowed to run it, so
"the handle came back before the work did" is shown rather than asserted.

Two acts:

1. **Merged deployment** - one process creates the task and executes it. An
   inline tool never reaches the broker; a task-eligible one rides one `command`
   message and comes back as one `state` message.
2. **Cross-replica read** - a second replica that created nothing, with a lite
   group of its own, answers `tasks/get` for that same task by tailing the
   channel's history.

    python examples/offline_walkthrough.py
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from typing import Any

import anyio
import anyio.to_thread
from rocketmq import ConsumeResult, Message

from mcp import Client
from mcp.server.mcpserver import MCPServer
from mcp_tasks_rocketmq.client import TasksClient
from mcp_tasks_rocketmq.rocketmq import (
    TAG_COMMAND,
    RocketMQTaskBackend,
    RocketMQTaskDispatcher,
    RocketMQTaskTailer,
    RocketMQTaskWorker,
    task_channel,
)
from mcp_tasks_rocketmq.tasks import Tasks
from mcp_tasks_rocketmq.wire import CreateTaskResult, GetTaskResult, TasksGetParams, TasksGetRequest

TOPIC = "mcp-tasks"

T0 = time.monotonic()


def log(msg: str) -> None:
    print(f"[{time.monotonic() - T0:6.3f}s] {msg}", flush=True)


class Loopback:
    """One topic, indexed twice: the parent subscriptions' TAG filter, and the channels."""

    def __init__(self) -> None:
        self.queue: list[Message] = []
        self.log: list[Message] = []
        """Everything ever sent, in order - the ledger as a broker would hold it."""
        self.consumers: list[LoopbackConsumer] = []

    def send(self, message: Message) -> str:
        message.seq = len(self.log)  # a channel is totally ordered: one broker, one queue
        self.queue.append(message)
        self.log.append(message)
        return f"msg-{message.seq}"

    def history(self, channel: str, since: int) -> list[Message]:
        """The records already on `channel` at or after `since` - what a first subscription is pushed."""
        return [m for m in self.log if m.lite_topic == channel and m.seq >= since]

    async def deliver(self) -> None:
        """Drain the queue, dispatching each message on a worker thread.

        Draining to empty covers a whole hop in one call: the `command` message
        makes the pool publish a `state` message, which is itself one to route.
        The listeners block that thread until they are done, so a drained queue
        is a settled store - no sleeping required to observe one.
        """
        while self.queue:
            message = self.queue.pop(0)
            for consumer in list(self.consumers):
                if consumer.filtered_in(message):
                    await anyio.to_thread.run_sync(consumer.listener.consume, message)
            for consumer in list(self.consumers):
                if consumer.due(message):
                    await anyio.to_thread.run_sync(consumer.listener.consume, message)


class LoopbackProducer:
    """Stands in for `rocketmq.Producer`."""

    def __init__(self, broker: Loopback) -> None:
        self._broker = broker
        self._running = False

    @property
    def is_running(self) -> bool:
        return self._running

    def startup(self) -> None:
        self._running = True

    def shutdown(self) -> None:
        self._running = False

    def send(self, message: Message) -> str:
        return self._broker.send(message)


class LoopbackConsumer:
    """Stands in for `rocketmq.PushConsumer` (a TAG filter) and `LitePushConsumer` (channels)."""

    def __init__(self, broker: Loopback, listener: Any, *, group: str, tag: str | None = None) -> None:
        self.listener = listener
        self.group = group
        self._tag = tag
        """The parent-topic face: the TAG this subscription filters on, `None` for no such face."""
        self._channels: set[str] = set()
        self._offsets: dict[str, int] = {}
        """Per (group, channel) position - which is why each replica needs a group of its own."""
        self._running = False
        self._broker = broker
        broker.consumers.append(self)

    @property
    def is_running(self) -> bool:
        return self._running

    def startup(self) -> None:
        self._running = True

    def shutdown(self) -> None:
        self._running = False

    def filtered_in(self, message: Message) -> bool:
        return self._tag is not None and message.tag == self._tag

    def due(self, message: Message) -> bool:
        """Whether this consumer's channel face is due `message`, advancing its offset."""
        if message.lite_topic not in self._channels:
            return False
        if message.seq < self._offsets.get(message.lite_topic, 0):
            return False  # already consumed: a subscription is not a replay
        self._offsets[message.lite_topic] = message.seq + 1
        return True

    def subscribe_lite(self, channel: str) -> None:
        """Join `channel`, which pushes its whole history to a pair that never consumed it."""
        self._channels.add(channel)
        for message in self._broker.history(channel, self._offsets.get(channel, 0)):
            self._offsets[channel] = message.seq + 1
            self.listener.consume(message)

    def unsubscribe_lite(self, channel: str) -> None:
        self._channels.discard(channel)  # the offset stays: re-subscribing is not a replay


def register_tools(mcp: MCPServer[Any]) -> None:
    """`word_count` runs as a task; `char_count` is answered inline."""

    @mcp.tool()
    async def word_count(corpus: str) -> dict[str, Any]:
        """Count words. Slow, so it runs as a task on a worker."""
        await anyio.sleep(0.05)
        words = corpus.split()
        return {"words": len(words), "unique": len(set(words))}

    @mcp.tool()
    def char_count(corpus: str) -> int:
        """Count characters. Fast, so the creating replica answers it inline."""
        return len(corpus)


async def read(client: Client, task_id: str) -> GetTaskResult:
    return await client.session.send_request(TasksGetRequest(params=TasksGetParams(task_id=task_id)), GetTaskResult)


def merged_replica(broker: Loopback) -> MCPServer[dict[str, Any]]:
    """The default deployment: one build wires the creating replica and its pool worker.

    Same three objects `build_server()` assembles from a `RocketMQConfig` - only
    the RocketMQ clients differ, which is the whole of what this file fakes.
    """
    tailer = RocketMQTaskTailer(topic=TOPIC)
    tailer.attach_consumer(LoopbackConsumer(broker, tailer, group="mcp-tasks-tailer-a"))
    dispatcher = RocketMQTaskDispatcher(LoopbackProducer(broker), tailer=tailer, topic=TOPIC)
    worker = RocketMQTaskWorker(LoopbackProducer(broker), topic=TOPIC)
    worker.attach_consumer(LoopbackConsumer(broker, worker, group="mcp-tasks-workers", tag=TAG_COMMAND))
    backend = RocketMQTaskBackend(dispatcher, worker, tailer=tailer, task_tools=frozenset({"word_count"}))
    mcp = MCPServer[dict[str, Any]]("replica-a", lifespan=backend.runtime, extensions=[backend.extension])
    register_tools(mcp)
    return mcp


def reader_replica(broker: Loopback) -> MCPServer[dict[str, Any]]:
    """A replica that creates nothing: no producer, no pool, only a ledger tail.

    Its group is its own, because a lite offset is per (group, channel): sharing
    one would mean two replicas taking each other's records.
    """
    tailer = RocketMQTaskTailer(topic=TOPIC)
    consumer = LoopbackConsumer(broker, tailer, group="mcp-tasks-tailer-b")
    tailer.attach_consumer(consumer)
    consumer.startup()
    tasks = Tasks(task_tools=frozenset({"word_count"}), ledger=tailer)
    mcp = MCPServer[dict[str, Any]]("replica-b", lifespan=tasks.runtime, extensions=[tasks])
    register_tools(mcp)
    return mcp


async def act_one(broker: Loopback) -> str:
    """The merged deployment, one message at a time. Returns the task's id."""
    log("ACT 1 - merged deployment: this process creates the task and executes it")
    async with Client(merged_replica(broker), extensions=[TasksClient()]) as client:
        log("1) char_count - not task-eligible, answered inline")
        counted = await client.call_tool("char_count", {"corpus": "call me ishmael"})
        log(f"   -> {counted.structured_content}, broker untouched ({len(broker.log)} messages sent)")

        log("2) word_count - submitted as the ledger's first record, answered with a handle")
        # `allow_claimed=True` opts out of `TasksClient`'s resolver, which would
        # otherwise poll this to completion and hand back the tool's own result.
        handle = await client.session.call_tool(
            "word_count", {"corpus": "call me ishmael some years ago never mind how long"}, allow_claimed=True
        )
        assert isinstance(handle, CreateTaskResult), handle
        log(f"   -> status={handle.status!r} taskId={handle.task_id}")
        log(f"      one message: tag={broker.log[-1].tag!r} channel={broker.log[-1].lite_topic!r}")

        log("3) the handle is readable before anything has executed")
        log(f"   -> tasks/get status={(await read(client, handle.task_id)).status!r} (the command is still queued)")

        log("4) delivering: the pool takes the command, runs the tool, publishes the state")
        await broker.deliver()
        landed = await read(client, handle.task_id)
        assert landed.result is not None
        log(f"   -> tasks/get status={landed.status!r} result={landed.result['structuredContent']}")
        log(f"      the channel now holds {len(broker.history(task_channel(handle.task_id), 0))} records:")
        for message in broker.history(task_channel(handle.task_id), 0):
            log(f"        seq={message.seq} tag={message.tag!r} status={json.loads(message.body)['status']!r}")
        return handle.task_id


async def act_two(broker: Loopback, task_id: str) -> None:
    """A replica that never saw the task answers for it anyway."""
    log("ACT 2 - cross-replica read: replica-b created nothing and has an empty store")
    async with Client(reader_replica(broker), extensions=[TasksClient()]) as client:
        log(f"5) tasks/get on replica-b for {task_id}")
        log("   the store misses, so the ledger is tailed: joining the channel pushes its whole history")
        answered = await read(client, task_id)
        assert answered.result is not None
        log(f"   -> status={answered.status!r} result={answered.result['structuredContent']}")
        log("6) the record is in replica-b's store now, so the next read is local")
        log(f"   -> status={(await read(client, task_id)).status!r}")


async def main() -> None:
    broker = Loopback()
    log(f"topic {TOPIC!r} (LiteTopic): `command` to the pool, `t-<taskId>` the task's ledger")
    log("groups: 'mcp-tasks-workers' (plain, the pool), 'mcp-tasks-tailer-a' / '-b' (one per replica)")
    print(flush=True)
    task_id = await act_one(broker)
    print(flush=True)
    await act_two(broker, task_id)
    print(flush=True)
    log(f"done - {len(broker.log)} messages on {TOPIC!r}, no broker involved")


if __name__ == "__main__":
    # The walkthrough narrates every broker event itself, so the plugin's own INFO
    # lines would only repeat it; raise this to see the messaging from inside.
    logging.basicConfig(level=logging.WARNING)
    asyncio.run(main())
