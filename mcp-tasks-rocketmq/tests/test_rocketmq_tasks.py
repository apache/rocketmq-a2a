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

"""The RocketMQ task pool (`mcp_tasks_rocketmq.rocketmq`): one topic, two faces, one ledger.

No client library and no broker: a fake `rocketmq` module stands in `sys.modules`
for the deferred imports, and a fake broker reproduces the one mechanism the whole
topology rests on - a lite message is indexed twice, so it reaches both a plain
subscription on its parent topic (subject to that subscription's TAG filter) and
whoever holds its channel. Delivery is explicit (`broker.deliver()`), so every
assertion about a task still being `working` is a fact rather than a race.

The fake dispatches on a worker thread, matching the real client: that is what
makes the worker's `run_coroutine_threadsafe` hop and the tailer's the code under
test rather than an accident. Both block that thread until they are done, so a
drained queue is a settled store, and a test needs no sleep to observe one.

Two properties of the real client are modelled because the read path leans on
them. A channel is pushed its whole history to a (group, channel) pair that has
never consumed it - `subscribe_lite` takes no offset option and needs none - and
is not replayed once that pair's offset has advanced, so a channel let go at its
terminal record is let go for good. And a subscription's offset is per (group,
channel), which is why every replica here tails through a consumer of its own.

The merged deployment (`RocketMQTaskBackend`) adds an ordering claim, so the fake
clients record their startups and shutdowns in `broker.trace`: what is asserted
is that the tailer outlives the pool.
"""

from __future__ import annotations

import asyncio
import enum
import json
import logging
import sys
import threading
import types as pytypes
from collections.abc import Sequence
from typing import Any

import anyio
import anyio.lowlevel
import anyio.to_thread
import pytest
from mcp_types import INTERNAL_ERROR
from mcp_tasks_rocketmq.rocketmq import (
    MAX_ACTIVE_TAILS,
    SETTLE_S,
    TAG_COMMAND,
    TAG_STATE,
    RocketMQTaskBackend,
    RocketMQTaskDispatcher,
    RocketMQTaskTailer,
    RocketMQTaskWorker,
    task_channel,
)
from mcp_tasks_rocketmq import server as server_module
from mcp_tasks_rocketmq.client import TasksClient
from mcp_tasks_rocketmq.config import RocketMQConfig
from mcp_tasks_rocketmq.server import build_server
from mcp_tasks_rocketmq.tasks import READ_DEADLINE_S, Tasks
from mcp_tasks_rocketmq.wire import CreateTaskResult, GetTaskResult, TasksGetParams, TasksGetRequest

from mcp import Client, MCPError
from mcp.server.mcpserver import MCPServer
from mcp.types import InputRequiredResult

pytestmark = pytest.mark.anyio


class _FakeConsumeResult(enum.Enum):
    SUCCESS = 0
    FAILURE = 1


class _FakeMessage:
    """A plain attribute bag standing in for `rocketmq.Message`."""

    topic: Any = None
    lite_topic: Any = None
    tag: Any = None
    body: Any = None
    message_id: Any = None
    seq: int = -1
    """The fake broker's own bookkeeping: where in the channel's ledger this record sits."""


@pytest.fixture(autouse=True)
def fake_rocketmq(monkeypatch: pytest.MonkeyPatch) -> pytypes.ModuleType:
    """Stand the fake `rocketmq` module into `sys.modules` for the deferred imports."""
    fake = pytypes.ModuleType("rocketmq")
    setattr(fake, "ConsumeResult", _FakeConsumeResult)
    setattr(fake, "Message", _FakeMessage)
    monkeypatch.setitem(sys.modules, "rocketmq", fake)
    return fake


class _FakeBroker:
    """One topic, indexed twice: a plain subscription's TAG filter and the channels."""

    def __init__(self) -> None:
        self.queue: list[_FakeMessage] = []
        self.log: list[_FakeMessage] = []
        """Everything ever sent, in order - the ledger as the broker holds it."""
        self.subscribers: list[_FakeConsumer] = []
        self.delivered: list[tuple[str, Any]] = []
        """Which consumer received which TAG - the two faces, observed."""
        self.refuse: str | None = None
        self.refuse_subscribe: str | None = None
        """A channel (or `_EVERY_CHANNEL`) whose subscription fails, the way an unreachable broker does."""
        self.trace: list[str] = []
        """Every client's startup and shutdown, in the order they happened."""

    def send(self, message: _FakeMessage) -> str:
        if self.refuse is not None and self.refuse in (_EVERY_CHANNEL, message.lite_topic):
            raise RuntimeError("broker unavailable")
        message.seq = len(self.log)  # a channel is totally ordered: one broker, one queue
        self.queue.append(message)
        self.log.append(message)
        return "msg-ok"

    def history(self, channel: str, since: int) -> list[_FakeMessage]:
        """The records already on `channel` at or after `since` - what a first subscription is pushed."""
        return [message for message in self.log if message.lite_topic == channel and message.seq >= since]

    def routes(self) -> list[tuple[Any, Any]]:
        """The TAG and channel of everything sent, which is the whole addressing."""
        return [(message.tag, message.lite_topic) for message in self.log]

    def bodies(self) -> list[Any]:
        return [json.loads(message.body) for message in self.log]

    def received_by(self, name: str) -> list[Any]:
        return [tag for consumer, tag in self.delivered if consumer == name]

    async def deliver(self) -> None:
        """Drain the queue, dispatching each message on a worker thread until nothing is left.

        Draining to empty covers the whole flow in one call: the submit message
        makes the worker publish a state message, which is itself one to route.
        """
        while self.queue:
            message = self.queue.pop(0)
            for consumer in list(self.subscribers):
                if consumer.subscribes(message):
                    await self._hand(consumer, message)  # the parent face: a plain subscription
            for consumer in list(self.subscribers):
                if consumer.takes(message):
                    await self._hand(consumer, message)  # the channel face: whoever is due it

    async def _hand(self, consumer: _FakeConsumer, message: _FakeMessage) -> None:
        self.delivered.append((consumer.name, message.tag))
        # On a thread, and the listener blocks it until the record is stored.
        await anyio.to_thread.run_sync(consumer.listener.consume, message)


class _FakeProducer:
    def __init__(self, broker: _FakeBroker, *, name: str = "producer") -> None:
        self._broker = broker
        self._name = name
        self.started = False
        self.stopped = False

    @property
    def is_running(self) -> bool:
        """What the real client exposes, and what `shutdown` keys off."""
        return self.started and not self.stopped

    def startup(self) -> None:
        self.started = True
        self._broker.trace.append(f"{self._name} up")

    def shutdown(self) -> None:
        if not self.is_running:
            raise RuntimeError(f"{self._name} is not running")  # the real client raises here
        self.stopped = True
        self._broker.trace.append(f"{self._name} down")

    def send(self, message: _FakeMessage) -> str:
        return self._broker.send(message)


class _FakeConsumer:
    def __init__(
        self,
        broker: _FakeBroker,
        listener: Any,
        *,
        filters: dict[str, str | None] | None = None,
        name: str = "consumer",
    ) -> None:
        self.listener = listener
        self.filters: dict[str, str | None] = dict(filters) if filters is not None else {}
        """The parent-topic face: topic -> the TAG it filters on, `None` for everything."""
        self.channels: set[str] = set()
        self.offsets: dict[str, int] = {}
        """Per (group, channel) position, which an unsubscribe leaves where it is."""
        self.subscribe_order: list[str] = []
        """Every channel subscription this consumer made, in order, repeats included."""
        self.started = False
        self.stopped = False
        self.name = name
        self.closed = threading.Event()
        """Set by `shutdown`, which a host may call from a thread."""
        self._broker = broker
        broker.subscribers.append(self)

    @property
    def is_running(self) -> bool:
        """What the real client exposes, and what `shutdown` keys off."""
        return self.started and not self.stopped

    def subscribes(self, message: _FakeMessage) -> bool:
        """Whether this consumer's parent-topic face receives `message`.

        The TAG filter is the broker's: a message the expression rejects never
        reaches `consume` at all.
        """
        if message.topic not in self.filters:
            return False
        expression = self.filters[message.topic]
        return expression is None or expression == message.tag

    def takes(self, message: _FakeMessage) -> bool:
        """Whether this consumer's channel face is due `message`, advancing its offset."""
        if message.lite_topic not in self.channels:
            return False
        if message.seq < self.offsets.get(message.lite_topic, 0):
            return False  # already consumed: a subscription is not a replay
        self.offsets[message.lite_topic] = message.seq + 1
        return True

    def startup(self) -> None:
        self.started = True
        self._broker.trace.append(f"{self.name} up")

    def shutdown(self) -> None:
        if not self.is_running:
            raise RuntimeError(f"{self.name} is not running")  # the real client raises here
        self.stopped = True
        self._broker.trace.append(f"{self.name} down")
        self.closed.set()

    def subscribe_lite(self, channel: str) -> None:
        if self._broker.refuse_subscribe in (_EVERY_CHANNEL, channel):
            raise RuntimeError("broker unavailable")
        self.channels.add(channel)
        self.subscribe_order.append(channel)
        # A (group, channel) pair that has never consumed is pushed the channel's whole
        # history; one whose offset has advanced is not. Delivered here, synchronously,
        # the way the client's own thread would - the listener blocks it until stored.
        for message in self._broker.history(channel, self.offsets.get(channel, 0)):
            self.offsets[channel] = message.seq + 1
            self._broker.delivered.append((self.name, message.tag))
            self.listener.consume(message)

    def unsubscribe_lite(self, channel: str) -> None:
        self.channels.discard(channel)  # the offset stays: re-subscribing is not a replay


TOPIC = "mcp-tasks"

CREATED_AT = "2026-01-01T00:00:00+00:00"
"""A fixed `createdAt`, for the records handed to a tailer directly rather than written by one."""

_EVERY_CHANNEL = "*"
"""Stands for any channel in `refuse` / `refuse_subscribe`, since a real one starts with `t-`."""


def _tailer(
    broker: _FakeBroker, *, name: str = "tailer", max_tails: int = MAX_ACTIVE_TAILS
) -> tuple[RocketMQTaskTailer, _FakeConsumer]:
    """One replica's ledger tail: the consumer is the group that replica owns."""
    tailer = RocketMQTaskTailer(topic=TOPIC, max_tails=max_tails)
    consumer = _FakeConsumer(broker, tailer, name=name)
    tailer.attach_consumer(consumer)
    return tailer, consumer


def _dispatcher(broker: _FakeBroker) -> tuple[RocketMQTaskDispatcher, RocketMQTaskTailer, _FakeConsumer]:
    """The write half of a replica, and the tail it follows its own channels through."""
    tailer, consumer = _tailer(broker)
    dispatcher = RocketMQTaskDispatcher(_FakeProducer(broker), tailer=tailer, topic=TOPIC)
    return dispatcher, tailer, consumer


def _register_tools(mcp: MCPServer[dict[str, Any]]) -> None:
    """The tools the tests run; `word_count` is the task-eligible one.

    A deliberately simple shape: the count is what the result assertions key on.
    """

    @mcp.tool()
    async def word_count(corpus: str) -> dict[str, Any]:
        """Count words, with the unique count alongside."""
        await anyio.sleep(0.05)
        words = corpus.split()
        return {"words": len(words), "unique": len(set(words))}

    @mcp.tool()
    def char_count(corpus: str) -> int:
        """Count characters. Fast, so the creating replica answers it inline."""
        return len(corpus)


async def _worker(broker: _FakeBroker, tools: MCPServer[dict[str, Any]] | None = None) -> RocketMQTaskWorker:
    """A started worker. `serve()` would do the startup and the loop binding together."""
    if tools is None:
        tools = MCPServer[dict[str, Any]]("worker")
        _register_tools(tools)
    worker = RocketMQTaskWorker(_FakeProducer(broker), topic=TOPIC, execute=tools.call_tool)
    worker.attach_consumer(_FakeConsumer(broker, worker, filters={TOPIC: TAG_COMMAND}, name="pool"))
    worker.bind_loop(asyncio.get_running_loop())
    await anyio.to_thread.run_sync(worker.startup)
    return worker


def _replica(broker: _FakeBroker) -> tuple[MCPServer[dict[str, Any]], _FakeConsumer]:
    """A creating replica: the story's extension with the RocketMQ dispatcher under it."""
    dispatcher, tailer, consumer = _dispatcher(broker)
    tasks = Tasks(task_tools=frozenset({"word_count"}), dispatcher=dispatcher, ledger=tailer)
    mcp = MCPServer[dict[str, Any]]("replica", lifespan=tasks.runtime, extensions=[tasks])
    _register_tools(mcp)
    return mcp, consumer


def _reader(
    broker: _FakeBroker,
) -> tuple[MCPServer[dict[str, Any]], Tasks, RocketMQTaskTailer, _FakeConsumer]:
    """A replica that created nothing: no producer, only a tail of its own to read through.

    The extension comes back too, because what a read leaves behind - the store, the
    channels still followed - is half of what these tests assert.
    """
    tailer, consumer = _tailer(broker, name="reader")
    tasks = Tasks(task_tools=frozenset({"word_count"}), ledger=tailer)
    mcp = MCPServer[dict[str, Any]]("reader", lifespan=tasks.runtime, extensions=[tasks])
    _register_tools(mcp)
    return mcp, tasks, tailer, consumer


def _merged(broker: _FakeBroker) -> tuple[RocketMQTaskBackend, MCPServer[dict[str, Any]]]:
    """The merged deployment: one server whose lifespan also runs the pool.

    Named clients, because what the merged tests assert is the order they come up
    and go down in. No registry is given to the worker - the tools it runs are the
    ones registered on this server.
    """
    tailer, _ = _tailer(broker)
    dispatcher = RocketMQTaskDispatcher(_FakeProducer(broker, name="submit"), tailer=tailer, topic=TOPIC)
    worker = RocketMQTaskWorker(_FakeProducer(broker, name="write-back"), topic=TOPIC)
    worker.attach_consumer(_FakeConsumer(broker, worker, filters={TOPIC: TAG_COMMAND}, name="pool"))
    backend = RocketMQTaskBackend(dispatcher, worker, tailer=tailer, task_tools=frozenset({"word_count"}))
    mcp = MCPServer[dict[str, Any]]("merged", lifespan=backend.runtime, extensions=[backend.extension])
    _register_tools(mcp)
    return backend, mcp


def _merged_without_worker(broker: _FakeBroker) -> tuple[RocketMQTaskBackend, MCPServer[dict[str, Any]]]:
    """The split deployment's server side: a creating replica whose pool lives elsewhere."""
    tailer, _ = _tailer(broker)
    dispatcher = RocketMQTaskDispatcher(_FakeProducer(broker, name="submit"), tailer=tailer, topic=TOPIC)
    backend = RocketMQTaskBackend(dispatcher, tailer=tailer, task_tools=frozenset({"word_count"}))
    mcp = MCPServer[dict[str, Any]]("replica", lifespan=backend.runtime, extensions=[backend.extension])
    _register_tools(mcp)
    return backend, mcp


async def _read(client: Client, task_id: str) -> GetTaskResult:
    return await client.session.send_request(TasksGetRequest(params=TasksGetParams(task_id=task_id)), GetTaskResult)


async def _handle(client: Client, corpus: str) -> CreateTaskResult:
    """Take the raw handle, so the test controls when the work is delivered."""
    handle = await client.session.call_tool("word_count", {"corpus": corpus}, allow_claimed=True)
    assert isinstance(handle, CreateTaskResult), handle
    return handle


async def _once_followed(consumer: _FakeConsumer, channel: str) -> None:
    """Wait until a read has joined `channel`, so what is written next reaches it.

    The join happens inside a `tasks/get` this test cannot await directly - the whole
    point of it is that the read is still waiting when the record is written.
    """
    with anyio.fail_after(5):
        while channel not in consumer.channels:
            await anyio.sleep(0.001)


def _record_body(task_id: str, status: str, **extra: Any) -> bytes:
    """A ledger record on the wire, for the cases that hand one to a tailer directly."""
    return json.dumps({"taskId": task_id, "status": status, **extra}).encode("utf-8")


def _message(channel: str, tag: str, body: bytes) -> _FakeMessage:
    message = _FakeMessage()
    message.topic = TOPIC
    message.lite_topic = channel
    message.tag = tag
    message.body = body
    return message


async def _collect(client: Client, task_id: str, into: list[GetTaskResult]) -> None:
    """One read, kept - for the read that is still waiting when the record is written."""
    into.append(await _read(client, task_id))


async def _deliver_record(
    tailer: RocketMQTaskTailer, task_id: str, status: str, *, tag: str = TAG_STATE, **fields: Any
) -> None:
    """Hand one ledger record to a tailer the way a delivery does: on a thread, blocking it."""
    message = _message(task_channel(task_id), tag, _record_body(task_id, status, **fields))
    await anyio.to_thread.run_sync(tailer.consume, message)


async def test_a_task_runs_on_the_worker_and_the_result_comes_back() -> None:
    """The whole flow: the handle returns while the work is still queued, a worker
    executes it, and the terminal result lands in the creating replica's store."""
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, replica_consumer = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "moby dick")
        assert handle.status == "working"
        # Nothing has executed yet: the submit message is still sitting in the queue.
        assert (await _read(client, handle.task_id)).status == "working"

        await broker.deliver()

        landed = await _read(client, handle.task_id)
        assert landed.status == "completed"
        assert landed.result is not None
        assert landed.result["structuredContent"] == {"words": 2, "unique": 2}
        # A terminal record ends the follow: the channel is let go once its state is in.
        assert task_channel(handle.task_id) not in replica_consumer.channels
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_the_submit_message_is_the_ledgers_first_record() -> None:
    """One send does both jobs: the pool's instruction and the task's opening record.

    Subscribe-then-send is the order this dispatcher chose, not one the broker
    forces: a first subscription is pushed the channel's history either way. What the
    send's ack is required for is the handle - the work is queued and the task is on
    the broker before the client is told it exists.
    """
    broker = _FakeBroker()
    mcp, replica_consumer = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "hamlet")
    channel = task_channel(handle.task_id)
    assert replica_consumer.subscribe_order == [channel]
    assert broker.routes() == [(TAG_COMMAND, channel)]
    assert broker.bodies() == [
        {
            "taskId": handle.task_id,
            "name": "word_count",
            "arguments": {"corpus": "hamlet"},
            "status": "working",
            "createdAt": handle.created_at,
        }
    ]


async def test_the_write_back_echoes_the_submit_so_one_record_is_a_whole_answer() -> None:
    """Self-containment, on the wire: the second record carries the first one's `createdAt`.

    A reader answers from one record alone, and answers with the `createdAt` the
    client's handle carries rather than a second reading of somebody's clock.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "asyoulikeit")
        await broker.deliver()
    written = broker.bodies()[1]
    assert written["createdAt"] == handle.created_at
    assert written["status"] == "completed"
    assert written["lastUpdatedAt"] >= written["createdAt"]
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_the_pool_never_receives_the_state_messages_it_publishes() -> None:
    """One topic, two faces, and the TAG filter is what keeps them apart.

    Both messages are written to the same channel *and* the same parent topic, so
    the pool's plain subscription would receive its own write-back and treat it as
    new work. `FilterExpression("command")` is what stops it; the tailer, which
    holds the channel, receives both and wants both.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "twelfth night")
        await broker.deliver()
        assert (await _read(client, handle.task_id)).status == "completed"
    channel = task_channel(handle.task_id)
    assert broker.routes() == [(TAG_COMMAND, channel), (TAG_STATE, channel)]
    assert broker.received_by("pool") == [TAG_COMMAND]
    assert broker.received_by("tailer") == [TAG_COMMAND, TAG_STATE]
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_the_tailer_records_the_submit_record_on_its_status_alone(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Both kinds of message are records, so the TAG is not consulted and nothing is skipped.

    The submit arrives tagged `command` and is written like any other record: one
    `working` snapshot, answered from a store that held nothing before it, and no
    warning per task in a serving process's log. It is not terminal, so the channel
    stays followed for whatever comes next.
    """
    caplog.set_level(logging.WARNING)
    broker = _FakeBroker()
    reader, _, tailer, consumer = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as client:
        await anyio.to_thread.run_sync(tailer.subscribe, "t-1")
        await _deliver_record(tailer, "t-1", "working", tag=TAG_COMMAND, createdAt=CREATED_AT)
        opened = await _read(client, "t-1")
    assert (opened.status, opened.created_at, opened.result) == ("working", CREATED_AT, None)
    assert task_channel("t-1") in consumer.channels
    assert caplog.records == []


async def test_a_replica_that_created_nothing_answers_from_a_record_written_after_it_joined() -> None:
    """The point of the ledger: `Mcp-Name` affinity stops being the read path's precondition.

    The reader has no store entry and no producer - only the channel name, which the
    task id gives it. It joins while the task is still queued: the opening record is
    pushed to it as history, and the write-back, written after it joined, is what
    completes the answer.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "macbeth")  # submitted, and nothing delivered yet

        reader, _, _, reader_consumer = _reader(broker)
        async with Client(reader, extensions=[TasksClient()]) as elsewhere:
            answers: list[GetTaskResult] = []
            async with anyio.create_task_group() as tg:
                tg.start_soon(_collect, elsewhere, handle.task_id, answers)
                await _once_followed(reader_consumer, task_channel(handle.task_id))
                await broker.deliver()  # the submit runs the tool, whose write-back reaches the reader
            answered = answers[0]
    assert answered.status == "completed"
    assert answered.created_at == handle.created_at  # echoed by the write-back: one record, whole answer
    assert answered.result is not None
    assert answered.result["structuredContent"] == {"words": 1, "unique": 1}
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_reader_that_joins_after_the_task_finished_reads_it_from_history() -> None:
    """Joining late is not too late: a first subscription is pushed the channel's whole history.

    `subscribe_lite` takes no offset option and needs none. The records arrive one at
    a time from the earliest, so the read does not answer at the first of them - it
    waits for the channel to go quiet and answers with the newest, which is why a task
    that finished long ago is `completed` on the first ask rather than `working`.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "cymbeline")
        await broker.deliver()
        assert (await _read(client, handle.task_id)).status == "completed"

    reader, _, _, reader_consumer = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as elsewhere:
        late = await _read(elsewhere, handle.task_id)
    assert (late.status, late.created_at) == ("completed", handle.created_at)
    assert late.result is not None
    assert late.result["structuredContent"] == {"words": 1, "unique": 1}
    assert reader_consumer.channels == set()  # the terminal record let the channel go again
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_read_of_a_task_still_running_answers_once_the_channel_goes_quiet() -> None:
    """The other side of the quiet window: one record with nothing after it is an answer.

    Waiting for a terminal record instead would burn the whole deadline on every task
    that is legitimately still running, so the window is what bounds this read.
    """
    broker = _FakeBroker()
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "pericles")  # submitted and never delivered: still running

    reader, _, _, _ = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as elsewhere:
        started = anyio.current_time()
        running = await _read(elsewhere, handle.task_id)
        waited = anyio.current_time() - started
    assert (running.status, running.created_at, running.result) == ("working", handle.created_at, None)
    assert SETTLE_S <= waited < READ_DEADLINE_S


async def test_a_channel_consumed_once_is_not_replayed(monkeypatch: pytest.MonkeyPatch) -> None:
    """What the offset costs: the history push is per (group, channel), not per subscription.

    A replica that let go at the terminal record and then lost its store entry - an
    eviction, a restart - cannot read that task again: re-following pushes nothing, and
    the answer is `INTERNAL_ERROR` rather than `INVALID_PARAMS`, because the ledger is
    still on the broker. This is why the store keeps what it has.
    """
    monkeypatch.setattr("mcp_tasks_rocketmq.tasks.READ_DEADLINE_S", 0.05)
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "timon")
        await broker.deliver()

    reader, tasks, _, reader_consumer = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as elsewhere:
        assert (await _read(elsewhere, handle.task_id)).status == "completed"
        assert reader_consumer.channels == set()
        tasks._store.pop(handle.task_id)  # noqa: SLF001 - a replica that lost its store entry
        with pytest.raises(MCPError) as gone:
            await _read(elsewhere, handle.task_id)
    assert gone.value.code == INTERNAL_ERROR
    assert reader_consumer.subscribe_order == [task_channel(handle.task_id)] * 2
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_terminal_record_ends_the_follow_and_the_reads_after_it() -> None:
    """One follow per task: the terminal record lets the channel go and the store answers the rest.

    A poll loop must not turn every request into a subscription, and a status the
    state machine cannot leave cannot be read to a different answer.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "othello")

        reader, _, _, reader_consumer = _reader(broker)
        async with Client(reader, extensions=[TasksClient()]) as elsewhere:
            answers: list[GetTaskResult] = []
            async with anyio.create_task_group() as tg:
                tg.start_soon(_collect, elsewhere, handle.task_id, answers)
                await _once_followed(reader_consumer, task_channel(handle.task_id))
                await broker.deliver()
            first = answers[0]
            again = await _read(elsewhere, handle.task_id)
    assert first == again
    assert reader_consumer.channels == set()  # let go at the terminal record
    assert reader_consumer.subscribe_order == [task_channel(handle.task_id)]  # and never followed again
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_task_that_asks_for_input_stays_followed_through_its_fallback() -> None:
    """`input_required` is not terminal, and nothing freezes at the first status seen.

    The state machine lets a task go back - `tasks/update` answers the request for
    input - so a reader that let go there, or that kept the furthest status it had
    ever seen, would answer a state the task has already left.
    """
    broker = _FakeBroker()
    reader, _, tailer, consumer = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as client:
        await anyio.to_thread.run_sync(tailer.subscribe, "t-2")
        await _deliver_record(tailer, "t-2", "input_required", createdAt=CREATED_AT, result={"content": []})
        asking = await _read(client, "t-2")
        assert asking.status == "input_required"
        assert task_channel("t-2") in consumer.channels  # still followed: the task can move

        await _deliver_record(tailer, "t-2", "working", createdAt=CREATED_AT)
        back = await _read(client, "t-2")
    assert (back.status, back.result) == ("working", None)
    assert task_channel("t-2") in consumer.channels


async def test_a_read_nothing_landed_for_is_not_an_unknown_task(monkeypatch: pytest.MonkeyPatch) -> None:
    """The distinction the read path exists to keep: unknown state, not absent task.

    A subscribe the broker refuses and a wait nothing landed in are both answered
    `INTERNAL_ERROR`. `INVALID_PARAMS` would tell a client holding a perfectly good
    handle that its task never existed, and the client would stop asking - so with a
    ledger behind the store, that answer is not one this path can give.
    """
    monkeypatch.setattr("mcp_tasks_rocketmq.tasks.READ_DEADLINE_S", 0.05)
    broker = _FakeBroker()
    reader, _, _, consumer = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as elsewhere:
        broker.refuse_subscribe = _EVERY_CHANNEL
        with pytest.raises(MCPError) as refused:
            await _read(elsewhere, "no-such-task")
        broker.refuse_subscribe = None
        with pytest.raises(MCPError) as silent:
            await _read(elsewhere, "no-such-task")
    assert refused.value.code == INTERNAL_ERROR
    assert silent.value.code == INTERNAL_ERROR
    assert consumer.subscribe_order == []  # the refused one never got as far as the client
    assert consumer.channels == set()  # and the timed-out one was let go


async def test_a_record_delivered_twice_is_the_same_snapshot() -> None:
    """At-least-once is a fact, and an idempotent tool is what makes it a harmless one.

    A write-back delivered again is the same record: same status, same result, same
    two timestamps, so overwriting the store with it moves nothing. The answer a read
    gives depends on neither which copy arrived first nor how many did.
    """
    broker = _FakeBroker()
    reader, _, tailer, _ = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as client:
        await anyio.to_thread.run_sync(tailer.subscribe, "t-6")
        record: dict[str, Any] = {"createdAt": CREATED_AT, "lastUpdatedAt": CREATED_AT, "result": {"content": []}}
        await _deliver_record(tailer, "t-6", "completed", **record)
        first = await _read(client, "t-6")
        await _deliver_record(tailer, "t-6", "completed", **record)  # the same record, again
        assert await _read(client, "t-6") == first
    assert first.status == "completed"


async def test_a_tool_run_twice_appends_a_record_that_changes_nothing() -> None:
    """The premise the ledger reads by: a re-run writes the same record, so the state holds.

    A redelivered submission runs the tool again and appends a second write-back, so
    the channel ends up holding the same terminal record twice. This replica is not
    even reading it any more - the first one let the channel go - which is the other
    half of why a duplicate costs nothing here.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "two gentlemen")
        await broker.deliver()
        first = await _read(client, handle.task_id)
        await anyio.to_thread.run_sync(worker.consume, broker.log[0])  # the submission, redelivered
        assert await _read(client, handle.task_id) == first
    assert broker.routes().count((TAG_STATE, task_channel(handle.task_id))) == 2
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_record_this_reader_does_not_understand_does_not_hide_the_task(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """A writer ahead of this reader must not make a task unreadable."""
    caplog.set_level(logging.WARNING)
    broker = _FakeBroker()
    reader, _, tailer, _ = _reader(broker)
    async with Client(reader, extensions=[TasksClient()]) as client:
        await anyio.to_thread.run_sync(tailer.subscribe, "t-5")
        await _deliver_record(tailer, "t-5", "working", createdAt=CREATED_AT)
        ahead = _message(task_channel("t-5"), TAG_STATE, _record_body("t-5", "deferred", result={}))
        assert await anyio.to_thread.run_sync(tailer.consume, ahead) is _FakeConsumeResult.SUCCESS
        assert (await _read(client, "t-5")).status == "working"
    assert "deferred" in caplog.text


async def test_a_tool_that_asks_for_input_is_not_recorded_as_completed() -> None:
    """`status` rides the wire because the executor decides it, not the message's shape.

    A hard-coded `completed` would hand the client an `InputRequiredResult` as if
    it were the tool's own result.
    """
    broker = _FakeBroker()

    async def asks_for_input(name: str, arguments: dict[str, Any]) -> InputRequiredResult:
        return InputRequiredResult(request_state="waiting")

    worker = RocketMQTaskWorker(_FakeProducer(broker), topic=TOPIC, execute=asks_for_input)
    worker.attach_consumer(_FakeConsumer(broker, worker, filters={TOPIC: TAG_COMMAND}, name="pool"))
    worker.bind_loop(asyncio.get_running_loop())
    await anyio.to_thread.run_sync(worker.startup)
    mcp, replica_consumer = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "measure for measure")
        await broker.deliver()
        assert (await _read(client, handle.task_id)).status == "input_required"
    assert task_channel(handle.task_id) in replica_consumer.channels
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_refused_submit_fails_the_call_instead_of_handing_out_a_handle() -> None:
    """The send's ack is the handle's precondition: it is the queued work and the opening record at once.

    And a submit that never got written leaves nothing to follow, so the channel
    subscription made for it is let go again.
    """
    broker = _FakeBroker()
    broker.refuse = _EVERY_CHANNEL
    mcp, replica_consumer = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        with pytest.raises(MCPError):
            await client.call_tool("word_count", {"corpus": "lear"})
    assert broker.queue == []
    assert replica_consumer.channels == set()


async def test_an_inline_tool_never_reaches_the_broker() -> None:
    """Augmentation stays per call: the fast tool is answered without any messaging."""
    broker = _FakeBroker()
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        result = await client.call_tool("char_count", {"corpus": "call me ishmael"})
    assert result.structured_content == {"result": 15}
    assert broker.log == []


async def test_a_raising_tool_is_published_as_a_completed_task() -> None:
    """A tool failure is a result, not a redelivery: it lands as `completed` + `isError`."""
    broker = _FakeBroker()
    tools: MCPServer[dict[str, Any]] = MCPServer[dict[str, Any]]("worker")

    @tools.tool()
    def word_count(corpus: str) -> dict[str, Any]:
        """Always fails."""
        raise ValueError(f"boom {corpus}")

    worker = await _worker(broker, tools)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "othello")
        await broker.deliver()
        landed = await _read(client, handle.task_id)
    assert landed.status == "completed"
    assert landed.result is not None
    assert landed.result["isError"] is True
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_an_unreadable_payload_is_acknowledged_not_redelivered() -> None:
    """A payload nobody can read is dropped: seeing it again would not help."""
    broker = _FakeBroker()
    worker = await _worker(broker)
    submit = _message("", TAG_COMMAND, b"not json")
    assert await anyio.to_thread.run_sync(worker.consume, submit) is _FakeConsumeResult.SUCCESS

    _, tailer, _ = _dispatcher(broker)
    state = _message(task_channel("t-1"), TAG_STATE, _record_body("t-1", "completed", result="not an object"))
    assert await anyio.to_thread.run_sync(tailer.consume, state) is _FakeConsumeResult.SUCCESS
    await anyio.to_thread.run_sync(worker.shutdown)


async def test_a_record_with_no_status_is_dropped() -> None:
    """The tailer reads `body.status` and nothing else, so a record without one says nothing."""
    broker = _FakeBroker()
    _, tailer, _ = _dispatcher(broker)
    statusless = _message(task_channel("t-2"), TAG_STATE, json.dumps({"taskId": "t-2", "result": {}}).encode("utf-8"))
    assert await anyio.to_thread.run_sync(tailer.consume, statusless) is _FakeConsumeResult.SUCCESS


async def test_a_refused_write_back_asks_for_redelivery() -> None:
    """The one retryable failure: the tool already ran, so its state must still get out."""
    broker = _FakeBroker()
    worker = await _worker(broker)
    broker.refuse = task_channel("t-9")
    submit = _message(
        "",
        TAG_COMMAND,
        json.dumps({"taskId": "t-9", "name": "word_count", "arguments": {"corpus": "a b"}}).encode("utf-8"),
    )
    assert await anyio.to_thread.run_sync(worker.consume, submit) is _FakeConsumeResult.FAILURE
    await anyio.to_thread.run_sync(worker.shutdown)


def test_startup_requires_a_consumer() -> None:
    """Both consumer roles refuse to start half-wired."""
    with pytest.raises(RuntimeError):
        RocketMQTaskTailer(topic=TOPIC).startup()
    broker = _FakeBroker()
    worker = RocketMQTaskWorker(_FakeProducer(broker), topic=TOPIC, execute=_never_executes)
    with pytest.raises(RuntimeError):
        worker.startup()


def test_a_client_that_never_started_survives_its_own_teardown() -> None:
    """The real client raises from `shutdown()` when it is not running, and every teardown
    here sits in a `finally`: a startup that failed must not be masked by the teardown
    that follows it, nor leave the process holding the client's threads."""
    broker = _FakeBroker()
    tailer, _ = _tailer(broker)
    tailer.shutdown()
    RocketMQTaskDispatcher(_FakeProducer(broker), tailer=tailer, topic=TOPIC).shutdown()
    RocketMQTaskWorker(_FakeProducer(broker), topic=TOPIC, execute=_never_executes).shutdown()
    assert broker.trace == []


def test_a_replica_follows_no_more_channels_than_its_cap() -> None:
    """A tail is a broker-side resource, so the count of them is bounded and the oldest goes.

    A task stuck short of a terminal status - its worker died - would otherwise hold
    one forever.
    """
    broker = _FakeBroker()
    tailer, consumer = _tailer(broker, max_tails=2)
    tailer.subscribe("t-a")
    tailer.subscribe("t-b")
    tailer.subscribe("t-a")  # a read of a task already followed: no new subscription, and it is the newest again
    assert consumer.subscribe_order == [task_channel("t-a"), task_channel("t-b")]

    tailer.subscribe("t-c")  # over the cap, so the least recently followed goes: t-b, not t-a
    assert consumer.channels == {task_channel("t-a"), task_channel("t-c")}
    assert consumer.subscribe_order[-1] == task_channel("t-c")


def test_the_worker_refuses_to_start_with_nothing_to_run_tools_through() -> None:
    """`execute` may arrive after construction - the merged backend binds the
    server's own `call_tool` - but a worker that never got one would consume work
    it cannot execute."""
    broker = _FakeBroker()
    worker = RocketMQTaskWorker(_FakeProducer(broker), topic=TOPIC)
    worker.attach_consumer(_FakeConsumer(broker, worker, filters={TOPIC: TAG_COMMAND}))
    with pytest.raises(RuntimeError):
        worker.startup()
    worker.bind_execute(_never_executes)
    worker.startup()


async def test_the_merged_backend_runs_the_task_it_submitted_to_itself() -> None:
    """One process, both roles: the handle still comes back while the work is
    queued, and the tool that runs is the server's own - the worker was given no
    registry of its own, only `server.call_tool`."""
    broker = _FakeBroker()
    _, mcp = _merged(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "moby dick")
        assert (await _read(client, handle.task_id)).status == "working"

        await broker.deliver()

        landed = await _read(client, handle.task_id)
    assert landed.status == "completed"
    assert landed.result is not None
    assert landed.result["structuredContent"] == {"words": 2, "unique": 2}
    channel = task_channel(handle.task_id)
    assert broker.routes() == [(TAG_COMMAND, channel), (TAG_STATE, channel)]
    # The store answered every read: the two records the tail received are the whole ledger traffic.
    assert broker.received_by("tailer") == [TAG_COMMAND, TAG_STATE]


async def test_a_backend_without_a_worker_submits_and_stays_working() -> None:
    """The split deployment's server side: the submit lands in the queue, and with
    no worker in this process the task stays `working` - the pool lives elsewhere."""
    broker = _FakeBroker()
    _, mcp = _merged_without_worker(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        handle = await _handle(client, "moby dick")
        assert (await _read(client, handle.task_id)).status == "working"
    channel = task_channel(handle.task_id)
    assert broker.routes() == [(TAG_COMMAND, channel)]
    # No pool consumer came up or down: the server's lifespan only ran the tailer.
    assert broker.trace == [
        "submit up",
        "tailer up",
        "tailer down",
        "submit down",
    ]


async def test_build_server_takes_your_own_tools(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The demo tools are defaults, not a cage: pass a register function and the
    task-tool names, and the built server lists yours - and runs them."""
    broker = _FakeBroker()

    def register(mcp: MCPServer[dict[str, Any]]) -> None:
        @mcp.tool()
        async def my_tool(text: str) -> str:
            """A tool of the embedder's own."""
            return f"mine: {text}"

    # The real client imports stand in for the fake broker's own clients.
    tailer, _ = _tailer(broker)
    monkeypatch.setattr(server_module, "build_tailer", lambda config: tailer)
    monkeypatch.setattr(
        server_module,
        "build_dispatcher",
        lambda config, tailer: RocketMQTaskDispatcher(_FakeProducer(broker, name="submit"), tailer=tailer, topic=TOPIC),
    )

    def fake_build_worker(config: RocketMQConfig) -> RocketMQTaskWorker:
        worker = RocketMQTaskWorker(_FakeProducer(broker, name="write-back"), topic=TOPIC)
        worker.attach_consumer(_FakeConsumer(broker, worker, filters={TOPIC: TAG_COMMAND}, name="pool"))
        return worker

    monkeypatch.setattr(server_module, "build_worker", fake_build_worker)

    config = RocketMQConfig(endpoints="fake:8080")
    mcp = build_server(config, task_tools=frozenset({"my_tool"}), register_tools=register)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        tools = await client.list_tools()
        assert {t.name for t in tools.tools} == {"my_tool"}
        handle = await client.session.call_tool("my_tool", {"text": "hello"}, allow_claimed=True)
        assert isinstance(handle, CreateTaskResult), handle
        await broker.deliver()
        landed = await _read(client, handle.task_id)
    assert landed.status == "completed"
    assert landed.result is not None
    assert landed.result["structuredContent"] == {"result": "mine: hello"}


async def test_the_merged_lifespan_closes_the_tailer_after_the_pool() -> None:
    """The ordering merging makes load-bearing.

    The tailer is what turns a write-back into a recorded result, so it closes last -
    after the pool has stopped consuming and drained. Closing it first would land
    results in a channel nobody is following, leaving this replica's store at
    `working` until a later read tails the ledger again.
    """
    broker = _FakeBroker()
    backend, mcp = _merged(broker)
    async with backend.runtime(mcp) as state:
        assert state == {}
    assert broker.trace == [
        "submit up",
        "tailer up",
        "write-back up",
        "pool up",
        "pool down",
        "write-back down",
        "tailer down",
        "submit down",
    ]


async def test_a_cancelled_lifespan_shuts_down_in_the_same_order() -> None:
    """A serving process is stopped by cancelling it, which is exactly when the
    order matters - so the teardown does not depend on being asked politely."""
    broker = _FakeBroker()
    backend, mcp = _merged(broker)
    with anyio.CancelScope() as scope:
        async with backend.runtime(mcp):
            scope.cancel()
            await anyio.lowlevel.checkpoint()
    assert broker.trace[4:] == ["pool down", "write-back down", "tailer down", "submit down"]


async def test_the_drain_waits_for_a_tool_that_is_still_running() -> None:
    """What the merged teardown leans on: the consumer closes first, and a result
    still on its way out still gets out."""
    broker = _FakeBroker()
    started, finish = anyio.Event(), anyio.Event()
    tools: MCPServer[dict[str, Any]] = MCPServer[dict[str, Any]]("worker")

    @tools.tool()
    async def word_count(corpus: str) -> dict[str, Any]:
        """Blocks until the test lets it finish."""
        started.set()
        await finish.wait()
        return {"words": len(corpus.split())}

    worker = await _worker(broker, tools)
    submit = _message(
        "",
        TAG_COMMAND,
        json.dumps({"taskId": "t-7", "name": "word_count", "arguments": {"corpus": "lear"}}).encode("utf-8"),
    )
    drained = anyio.Event()

    async def deliver_submit() -> None:
        await anyio.to_thread.run_sync(worker.consume, submit)

    async def drain() -> None:
        await anyio.to_thread.run_sync(worker.stop_consuming)
        await anyio.to_thread.run_sync(worker.wait_for_inflight)
        drained.set()

    async with anyio.create_task_group() as tg:
        tg.start_soon(deliver_submit)
        with anyio.fail_after(5):
            await started.wait()
        tg.start_soon(drain)
        with anyio.fail_after(5):
            await anyio.to_thread.run_sync(worker._consumer.closed.wait)
        # Consuming has stopped with the tool still running: nothing published yet,
        # and the drain cannot be over.
        assert not drained.is_set()
        assert broker.log == []
        finish.set()
    assert drained.is_set()
    assert broker.routes() == [(TAG_STATE, task_channel("t-7"))]
    await anyio.to_thread.run_sync(worker.close_producer)


async def _never_executes(name: str, arguments: dict[str, Any]) -> Any:  # pragma: no cover - wiring guard only
    raise AssertionError("the half-wired worker must not execute anything")


async def test_the_client_resolves_a_task_into_the_tools_result_without_the_caller_polling() -> None:
    """The transparent path, end to end: `call_tool` returns what the tool returned.

    No `allow_claimed` here, so the handle never reaches the caller - the client's
    claim resolves it by polling `tasks/get`, and the delivery that lets the pool
    run happens while that polling is in flight. What this covers beyond the
    resolver's own tests is the SDK's routing: that the claim is registered for
    `resultType: "task"` at all.
    """
    broker = _FakeBroker()
    worker = await _worker(broker)
    mcp, _ = _replica(broker)
    async with Client(mcp, extensions=[TasksClient()]) as client:
        async with anyio.create_task_group() as tg:
            tg.start_soon(_deliver_once_submitted, broker)
            result = await client.call_tool("word_count", {"corpus": "moby dick"})
    assert result.structured_content == {"words": 2, "unique": 2}
    assert broker.routes() == [
        (TAG_COMMAND, task_channel(broker.bodies()[0]["taskId"])),
        (TAG_STATE, task_channel(broker.bodies()[0]["taskId"])),
    ]
    await anyio.to_thread.run_sync(worker.shutdown)


async def _deliver_once_submitted(broker: _FakeBroker) -> None:
    """Deliver as soon as there is something to deliver, so the resolver's poll is racing it."""
    with anyio.fail_after(5):
        while not broker.queue:
            await anyio.sleep(0.001)
    await broker.deliver()
