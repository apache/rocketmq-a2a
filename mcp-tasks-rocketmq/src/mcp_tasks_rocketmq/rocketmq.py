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

"""Run MCP tasks on a RocketMQ worker pool, with each task's own lite channel as its ledger.

One parent topic and one channel per task (`t-<taskId>`) carry the whole
lifecycle. A lite message is indexed twice, so one write reaches two faces: the
parent topic's own ConsumeQueue, where the pool consumes it under a TAG filter,
and the task's channel, where every replica following that task receives it. The
submit message is both the pool's instruction and the task's first record, which
is what lets a replica that created nothing answer a `tasks/get` for it.

`README.md` ("Architecture") has the mechanism in full: the two faces, the wire
format, why reading the ledger is following it, what the at-least-once premise
costs, the shutdown order a merged deployment has to keep, and which thread each
callback arrives on.

Four objects, one protocol between them - the body format and the two TAGs are
shared, which is why they live in one module:

- `RocketMQTaskDispatcher` - submits a task, writing the ledger's first record.
- `RocketMQTaskWorker` - consumes `command`, runs the tool, publishes `state`.
- `RocketMQTaskTailer` - follows a task's channel, writing records into the store.
- `RocketMQTaskBackend` - runs all three in one process, in the right order.

RocketMQ is an optional dependency: nothing is imported until a client runs.
"""

# pyright: reportMissingImports=false, reportUnknownVariableType=false, reportUnknownMemberType=false, reportUnknownArgumentType=false
# The `rocketmq` client is an optional runtime dependency, imported lazily below.

from __future__ import annotations

import asyncio
import json
import logging
import threading
from collections import OrderedDict
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager, contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import anyio
import anyio.to_thread
from anyio.abc import TaskGroup

import mcp.types as types
from mcp.server.mcpserver import MCPServer
from mcp.types import TextContent
from mcp_tasks_rocketmq.tasks import (
    Record,
    Tasks,
    ToolExecutor,
    executed_status,
)
from mcp_tasks_rocketmq.wire import TASK_STATUSES, TERMINAL_STATUSES, TaskStatus

logger = logging.getLogger(__name__)

__all__ = [
    "TAG_COMMAND",
    "TAG_STATE",
    "RocketMQTaskBackend",
    "RocketMQTaskDispatcher",
    "RocketMQTaskTailer",
    "RocketMQTaskWorker",
    "task_channel",
]

DRAIN_SECONDS = 10.0
"""How long a shutdown waits for tools that are still running.

Bounded on purpose: draining is what keeps a result from being published into a
closed channel, but a tool that never returns must not hold the process open.
"""

MAX_ACTIVE_TAILS = 64
"""How many channels one replica follows at once, and the point of eviction.

A tail is a broker-side resource - a subscription and an offset per (group,
channel) - and a task stuck short of a terminal status because its worker died
holds one forever. Least-recently-followed goes first when the cap is reached.

Eviction is not free and is not recoverable by reading again: a channel is pushed
its whole history to a (group, channel) pair that has never consumed it, and this
replica's offset has already advanced past whatever it consumed, so a task evicted
mid-flight is answered by the records still to come, and one evicted after its
terminal record is not answered at all.

Kept low on purpose: the client enforces a quota of its own, granted by the broker
in its settings, and refuses a subscription past it with
`LiteSubscriptionQuotaExceededException`. This cap is the one the replica controls.
"""

SETTLE_S = 0.2
"""How long a read waits for a channel to go quiet before answering with what it has.

A subscription is pushed the channel's records one at a time from the earliest, so
the first to land is the ledger's *opening* record: answering the moment it arrives
would report `working` for a task that finished long ago. Waiting out a quiet window
is what makes the answer the newest record instead of the first one - which is what
reading a ledger means. Small, because the records of one task are written
milliseconds apart, and it is paid only by a read that missed the store.
"""

TAG_COMMAND = "command"
"""The TAG the pool subscribes: this message tells a worker to do something."""

TAG_STATE = "state"
"""The TAG the pool filters out: this message reports a task's state.

Not `status`, deliberately - the TAG is consumption-face routing and `body.status`
is the protocol state, and one name for both would invite reading the routing as
the state machine. The tailer does not look at either TAG: it wants the whole
ledger, and the submit message is its first record.
"""

_TASK_CHANNEL_PREFIX = "t-"
"""Channel namespace for one task's ledger: `t-<taskId>`.

A lite channel name may hold only alphanumerics, hyphens and underscores; the
broker rejects anything else with `40020`. Hence a hyphen rather than a colon.
"""


def task_channel(task_id: str) -> str:
    """The lite channel carrying `task_id`'s whole lifecycle - its ledger.

    The task id is already the routing key the protocol chose (`Mcp-Name`), so no
    hashing: publisher, subscriber and reader agree by construction. Task ids are
    hex, so the result stays inside the broker's channel-name character set.
    """
    return _TASK_CHANNEL_PREFIX + task_id


class RocketMQTaskDispatcher:
    """A `TaskDispatcher` that submits work to a RocketMQ pool instead of running it here.

    Construct with a producer built for the parent topic and the tailer this replica
    follows channels through, then hand both to whatever owns the lifespan (see
    `server.py`):

        tailer = RocketMQTaskTailer(topic="mcp-tasks")
        tailer.attach_consumer(LitePushConsumer(config, group, "mcp-tasks", tailer))
        dispatcher = RocketMQTaskDispatcher(producer, tailer=tailer, topic="mcp-tasks")
        backend = RocketMQTaskBackend(dispatcher, worker, tailer=tailer, task_tools=...)
    """

    def __init__(self, producer: Any, *, tailer: RocketMQTaskTailer, topic: str) -> None:
        self._producer = producer
        self._tailer = tailer
        self._topic = topic

    def startup(self) -> None:
        """Start the producer. Synchronous like the client itself; the tailer starts its own."""
        self._producer.startup()
        logger.info("task dispatcher started on %r", self._topic)

    def shutdown(self) -> None:
        """Close the producer. Safe when never started."""
        _stop(self._producer)

    def bind(self, *, execute: ToolExecutor, record: Record, tasks: TaskGroup) -> None:
        """Nothing to keep: this role only submits.

        The store's writer belongs to the tailer, which `Tasks` binds itself through
        `ledger=`; the tool dispatch and the task group belong to the worker's
        process, not to the one taking the request.
        """

    async def dispatch(self, task_id: str, params: types.CallToolRequestParams, *, created_at: str) -> None:
        """Follow the task's channel, then write the task's first ledger record.

        That record is the submit message itself: the pool reads it as an
        instruction (TAG `command`), the ledger reads it as `working` since
        `created_at`. Returning after the send's ack is what makes the handle honest
        - the work is queued and the task is on the broker before the client is told
        it exists.

        Following first is what the ledger reads by, though it is not what makes the
        records arrive: a channel is pushed its whole history to a (group, channel)
        pair that has never consumed it, so a replica that subscribes late still
        reads the task from its first record. A send that fails drops the
        subscription again - with no first record written, there is nothing to follow.
        """
        from rocketmq import Message  # deferred: optional dependency

        await anyio.to_thread.run_sync(self._tailer.subscribe, task_id)
        message = Message()
        message.topic = self._topic
        message.lite_topic = task_channel(task_id)
        message.tag = TAG_COMMAND
        message.body = json.dumps(
            {
                "taskId": task_id,
                "name": params.name,
                "arguments": params.arguments or {},
                "status": "working",
                "createdAt": created_at,
            }
        ).encode("utf-8")
        try:
            # The send is blocking (with the client's own retry/backoff); keep it off
            # the event loop so a broker stall cannot wedge the whole server.
            await anyio.to_thread.run_sync(self._producer.send, message)
        except Exception:
            await anyio.to_thread.run_sync(self._tailer.unsubscribe, task_id)
            raise


@dataclass
class _Tail:
    """One followed channel: what has landed on it, and who is waiting to hear."""

    records: int = 0
    """How many records have reached the store; each one re-arms a wait."""

    status: TaskStatus | None = None
    """The newest record's status, which is what ends a wait early at a terminal one."""

    waiter: anyio.Event | None = None


class RocketMQTaskTailer:
    """This replica's tail of every task channel it has a reason to follow.

    The `TaskLedger` of the story's seam, and the same object the dispatcher
    subscribes through: creating a task and reading one are not two roles, only two
    moments at which a channel gets followed. Both write into one store, through the
    `Record` the lifespan binds.

    One tailer per replica, and one consumer group per replica
    (`mcp-tasks-tailer-<replica>`): a lite subscription's offset is per (group,
    channel), so two replicas sharing a group would take each other's records and
    reset each other's position on the same task.

    Attach the `LitePushConsumer` bound to the parent topic with this tailer as its
    listener, then start it from the lifespan:

        tailer = RocketMQTaskTailer(topic="mcp-tasks")
        tailer.attach_consumer(LitePushConsumer(config, group, "mcp-tasks", tailer))
        mcp = MCPServer("...", lifespan=..., extensions=[Tasks(task_tools=..., ledger=tailer)])
    """

    def __init__(self, *, topic: str, max_tails: int = MAX_ACTIVE_TAILS) -> None:
        self._topic = topic
        self._max_tails = max_tails
        self._consumer: Any = None
        self._record: Record | None = None
        # The server's event loop, captured at `bind`: `consume` arrives on the
        # client's thread and the store write belongs to the loop.
        self._loop: asyncio.AbstractEventLoop | None = None
        # Held for the bookkeeping alone, never across a client call. `subscribe_lite`
        # is idempotent, so nothing here decides whether to make one; what this tracks
        # is the cap, and which tasks a record has already landed for.
        self._lock = threading.Lock()
        self._tails: OrderedDict[str, _Tail] = OrderedDict()

    def attach_consumer(self, consumer: Any) -> None:
        """Attach the `LitePushConsumer` task channels are followed through.

        The consumer must be bound to the parent topic, built with this tailer as its
        message listener, and built with this replica's own group - which is why
        neither can be a constructor argument here.
        """
        self._consumer = consumer

    def startup(self) -> None:
        """Start the consumer. Synchronous like the client itself.

        No channel is followed yet: a channel is subscribed per task, by `dispatch`
        before it submits and by a store-missing `tasks/get`.

        Raises:
            RuntimeError: `attach_consumer` was never called.
        """
        if self._consumer is None:
            raise RuntimeError("attach_consumer() must be called before startup()")
        self._consumer.startup()
        logger.info("task tailer started on %r", self._topic)

    def shutdown(self) -> None:
        """Stop the consumer. Safe when never started.

        The channels go with it: subscriptions are the consumer's, and the offsets
        stay on the broker under this replica's group.
        """
        _stop(self._consumer)

    def bind(self, *, record: Record) -> None:
        """Keep the store's writer and the loop it lives on; called once per lifespan."""
        self._record = record
        try:
            self._loop = asyncio.get_running_loop()
        except RuntimeError:  # pragma: no cover - `bind` runs from the server lifespan
            self._loop = None

    def subscribe(self, task_id: str) -> None:
        """Follow `task_id`'s channel. Idempotent, and blocking like the client.

        The client's `subscribe_lite` takes no offset option, and needs none here: a
        (group, channel) pair that has never consumed is pushed the channel's whole
        history, so a replica joining late reads the task from its first record.
        What it does not get is a replay - once this replica's offset has advanced
        past a record, re-following the channel does not bring it back, which is why
        a channel let go at its terminal record is let go for good.

        Re-following a channel already held is a no-op that reorders nothing but its
        own eviction priority. What a subscribe cannot tell is a subscription the
        broker refused: the client logs that and returns, so a failure to follow
        shows up as a wait that times out rather than as an exception. The quota and
        not-running refusals do raise, and a read turns either into `INTERNAL_ERROR`.
        """
        assert self._consumer is not None  # startup() ran, or the lifespan failed loudly
        with self._lock:
            if task_id in self._tails:
                self._tails.move_to_end(task_id)
                return
            crowded_out = self._over_cap()
            self._tails[task_id] = _Tail()
        for stale in crowded_out:
            logger.warning("task %s: dropping its tail to stay within %d", stale, self._max_tails)
            self._let_go(stale)
        self._consumer.subscribe_lite(task_channel(task_id))

    def unsubscribe(self, task_id: str) -> None:
        """Stop following `task_id`'s channel. Safe when it is not being followed."""
        with self._lock:
            if task_id not in self._tails:
                return
            del self._tails[task_id]
        self._let_go(task_id)

    async def wait_until_present(self, task_id: str, *, deadline_s: float) -> None:
        """Wait until this task's ledger has been read as far as it currently goes.

        Records arrive one at a time from the earliest, so the wait does not end at
        the first of them: a non-terminal record is given `SETTLE_S` to be followed
        by another, and the wait ends at whichever comes first - a terminal record
        (nothing valid follows one), a quiet channel (what is stored is the newest
        record), or the deadline. The store is the answer, not this method: a channel
        let go at its terminal record has already put the snapshot there.
        """
        with anyio.move_on_after(deadline_s):
            while True:
                with self._lock:
                    tail = self._tails.get(task_id)
                    if tail is None:
                        return  # let go: at a terminal record, or crowded out by the cap
                    if tail.status in TERMINAL_STATUSES:
                        return
                    # One event per channel, shared by concurrent reads of the same task
                    # and made here rather than in `subscribe`: this is the loop's thread.
                    waiter = tail.waiter if tail.waiter is not None else anyio.Event()
                    tail.waiter = waiter
                    seen = tail.records
                if seen == 0:
                    await waiter.wait()  # nothing stored yet: the deadline is the only bound
                    continue
                with anyio.move_on_after(SETTLE_S):
                    await waiter.wait()
                    continue  # another record landed inside the window, so it is newer: re-check
                return  # the channel went quiet, and what is stored is its newest record

    def consume(self, message: Any) -> Any:
        """RocketMQ listener callback: put one ledger record into the store.

        Runs on the client's thread and blocks it until the record is stored, so a
        delivery is over when this returns. Both kinds of message are records - the
        submit is the ledger's first - so the TAG is not consulted and nothing is
        skipped: `body.status` alone says what a record means, and a channel is in
        order, so the newest one is the task's state.

        A payload that cannot be read, or one carrying a status this reader does not
        know, is logged and acknowledged rather than redelivered: seeing it again
        would not make it readable, the ledger still holds it for whoever can read
        it, and a writer ahead of this one must not make a task unreadable.
        Acknowledging what *was* understood is not optional either - an unacked
        record accrues redeliveries and ends up in the dead-letter topic, which is a
        read destroying the ledger it reads.
        """
        from rocketmq import ConsumeResult  # deferred: optional dependency

        payload = _decode(message, "status")
        if payload is None:
            return ConsumeResult.SUCCESS
        task_id, status = payload["taskId"], payload["status"]
        if status not in TASK_STATUSES:
            logger.warning("task %s: ledger record with unknown status %r; skipping", task_id, status)
            return ConsumeResult.SUCCESS
        result = payload.get("result")
        if result is not None and not isinstance(result, dict):
            logger.warning("task %s: ledger record whose result is not an object; dropping", task_id)
            return ConsumeResult.SUCCESS
        landing = self._land(
            task_id,
            status,
            result,
            created_at=_string(payload, "createdAt"),
            last_updated_at=_string(payload, "lastUpdatedAt"),
        )
        if _on_a_loop():  # delivered in-process: the loop this runs on is the one to use
            asyncio.create_task(landing)
        else:
            assert self._loop is not None  # bind() ran before any channel was followed
            asyncio.run_coroutine_threadsafe(landing, self._loop).result()
        return ConsumeResult.SUCCESS

    async def _land(
        self,
        task_id: str,
        status: TaskStatus,
        result: dict[str, Any] | None,
        *,
        created_at: str | None,
        last_updated_at: str | None,
    ) -> None:
        """Store one record, wake whoever is waiting on it, and let go at a terminal status.

        The record is a whole snapshot, so storing it is an overwrite and the status
        is taken as written: a task that went `input_required` and came back is
        followed through both, which is why letting go waits for a status the state
        machine cannot leave.
        """
        assert self._record is not None  # only reachable while the lifespan holds the binding
        try:
            await self._record(task_id, result, status=status, created_at=created_at, last_updated_at=last_updated_at)
        except Exception:  # boundary: the record is on the broker; a failed store write must not wedge delivery
            logger.exception("task %s: storing a %s ledger record failed", task_id, status)
            return
        logger.info("task %s: %s landed from %s", task_id, status, task_channel(task_id))
        self._mark_landed(task_id, status)
        if status in TERMINAL_STATUSES:
            # Off the loop: `unsubscribe_lite` is a client call, and this coroutine is
            # what the consumer's own thread is blocked on.
            await anyio.to_thread.run_sync(self.unsubscribe, task_id)

    def _mark_landed(self, task_id: str, status: TaskStatus) -> None:
        """On the loop: one more record for this task is in the store, so a wait re-arms."""
        with self._lock:
            tail = self._tails.get(task_id)
            if tail is None:
                return
            tail.records += 1
            tail.status = status
            waiter, tail.waiter = tail.waiter, None
        if waiter is not None:
            waiter.set()

    def _let_go(self, task_id: str) -> None:
        """Drop the channel on the broker, and its bookkeeping on the loop."""
        if self._consumer is not None:
            self._consumer.unsubscribe_lite(task_channel(task_id))
        if self._loop is None:
            return
        if _on_a_loop():
            self._forget(task_id)
        else:
            self._loop.call_soon_threadsafe(self._forget, task_id)

    def _forget(self, task_id: str) -> None:
        """On the loop: nothing more will be pushed for this task, so stop waiting on it.

        Waking the waiter is the point - a wait on a channel this replica has let go
        of ends at once instead of running its deadline out.
        """
        with self._lock:
            tail = self._tails.get(task_id)
            waiter = tail.waiter if tail is not None else None
            if tail is not None:
                tail.waiter = None
        if waiter is not None:
            waiter.set()

    def _over_cap(self) -> list[str]:
        """The tasks to let go of to make room for one more. Caller holds the lock."""
        crowded_out: list[str] = []
        while len(self._tails) >= self._max_tails:
            stale, _ = self._tails.popitem(last=False)
            crowded_out.append(stale)
        return crowded_out


class RocketMQTaskWorker:
    """Consume submitted work off the parent topic, run the tool, publish the state.

    Holds no MCP connection: all it needs is tool dispatch, which arrives as
    `execute`. A standalone process passes an `MCPServer` it uses purely as a
    registry; merged into a serving process, `bind_execute` hands it that
    server's own `call_tool`. Scale the pool by starting more of these under the
    same consumer group.

    Its subscription carries `FilterExpression(TAG_COMMAND)`, and that filter is
    load-bearing rather than tidy: the state messages this worker publishes land
    in the same parent topic's queues, so a pool subscribing everything would
    consume its own output as if it were work.
    """

    def __init__(self, producer: Any, *, topic: str, execute: ToolExecutor | None = None) -> None:
        self._producer = producer
        self._topic = topic
        self._execute = execute
        self._consumer: Any = None
        self._loop: asyncio.AbstractEventLoop | None = None
        # In-flight bookkeeping lives on the consumer's threads, so it is a lock
        # and a threading event rather than anything anyio: `wait_for_inflight`
        # blocks on it from a thread while the loop keeps driving the tools.
        self._lock = threading.Lock()
        self._running = 0
        self._idle = threading.Event()
        self._idle.set()
        self.ready = anyio.Event()
        """Set once `serve` has the clients up, so a host can wait before submitting work."""

    def attach_consumer(self, consumer: Any) -> None:
        """Attach the `PushConsumer` subscribed to the parent topic's `command` TAG, with this worker as listener."""
        self._consumer = consumer

    def startup(self) -> None:
        """Start both clients. The subscription rides the consumer's own construction.

        Raises:
            RuntimeError: `attach_consumer` was never called, or nothing supplied
                the tool dispatch submitted work would run through.
        """
        if self._consumer is None:
            raise RuntimeError("attach_consumer() must be called before startup()")
        if self._execute is None:
            raise RuntimeError("execute must be given to the constructor or to bind_execute() before startup()")
        self._producer.startup()
        self._consumer.startup()
        logger.info("task worker started on %r", self._topic)

    def stop_consuming(self) -> None:
        """Stop taking new work, leaving the producer up: what is running still has a result to publish."""
        _stop(self._consumer)

    def wait_for_inflight(self) -> None:
        """Block until no submitted task is still executing.

        Call this from a thread: the tools are being driven on the event loop,
        which has to keep running for them to finish.
        """
        if not self._idle.wait(DRAIN_SECONDS):
            logger.warning("%d task(s) still running after %ss; closing anyway", self._running, DRAIN_SECONDS)

    def close_producer(self) -> None:
        """Close the write-back producer. Only sound once `wait_for_inflight` has returned."""
        _stop(self._producer)

    def shutdown(self) -> None:
        """Stop consuming, let what is running finish, then close the producer. Safe when never started."""
        self.stop_consuming()
        self.wait_for_inflight()
        self.close_producer()

    async def serve(self) -> None:
        """Capture the serving loop, start the clients, and run until cancelled."""
        self.bind_loop(asyncio.get_running_loop())
        await anyio.to_thread.run_sync(self.startup)
        self.ready.set()
        try:
            await anyio.sleep_forever()
        finally:
            # Shielded because the shutdown asking for the drain arrives as the
            # cancellation that got us here, and a cancelled scope refuses the
            # thread hop outright.
            with anyio.CancelScope(shield=True):
                await anyio.to_thread.run_sync(self.shutdown)

    def bind_loop(self, loop: asyncio.AbstractEventLoop) -> None:
        """Set the loop tool coroutines run on. `serve` does this; a host driving the
        clients itself calls it from async context before any message can arrive."""
        self._loop = loop

    def bind_execute(self, execute: ToolExecutor) -> None:
        """Set the tool dispatch submitted work runs through.

        A merged deployment's registry is the serving server's own and does not
        exist until its lifespan runs, so `execute` cannot always be a
        constructor argument.
        """
        self._execute = execute

    def consume(self, message: Any) -> Any:
        """RocketMQ listener callback: run one task to completion and publish its state.

        Runs on the client's thread and blocks it for the tool's duration, so the
        consumer's thread count is the pool's concurrency. A tool that fails
        publishes an `isError` result and acknowledges: letting the consumer retry
        instead would leave the task `working` until the retries ran out. Only a
        failed publish is redelivered - that is the one failure another attempt
        can fix, at the price of running the tool again, which is what at-least-once
        costs and why the record it writes has to be the same snapshot both times.
        """
        from rocketmq import ConsumeResult  # deferred: optional dependency

        payload = _decode(message, "name")
        if payload is None:
            return ConsumeResult.SUCCESS
        task_id, name = payload["taskId"], payload["name"]
        arguments = payload.get("arguments") or {}
        if not isinstance(name, str) or not isinstance(arguments, dict):
            logger.warning("task %s: unusable tool name or arguments; dropping", task_id)
            return ConsumeResult.SUCCESS
        with self._in_flight():
            logger.info("task %s: running tool %r", task_id, name)
            result, status = self._run_tool(task_id, name, arguments)
            try:
                self._publish(task_id, result, status, created_at=_string(payload, "createdAt"))
            except Exception:  # boundary: the tool already ran, so let the broker redeliver the write-back
                logger.exception("task %s: publishing the result failed; asking for redelivery", task_id)
                return ConsumeResult.FAILURE
        return ConsumeResult.SUCCESS

    @contextmanager
    def _in_flight(self) -> Iterator[None]:
        """Count one task for as long as it is executing, write-back included.

        The write-back is inside on purpose: a drain that let the producer close
        while a result was still being sent would defeat itself.
        """
        with self._lock:
            self._running += 1
            self._idle.clear()
        try:
            yield
        finally:
            with self._lock:
                self._running -= 1
                if self._running == 0:
                    self._idle.set()

    def _run_tool(self, task_id: str, name: str, arguments: dict[str, Any]) -> tuple[dict[str, Any], TaskStatus]:
        """Run `name` on the serving loop and return its outcome in wire form, with its status."""
        assert self._loop is not None  # `serve()`/`bind_loop()` ran before any message could arrive
        try:
            future = asyncio.run_coroutine_threadsafe(self._invoke(name, arguments), self._loop)
            outcome = future.result()
        except Exception as exc:  # boundary: every tool failure is a completed task carrying isError
            logger.exception("task %s raised while running tool %r", task_id, name)
            outcome = types.CallToolResult(content=[TextContent(type="text", text=str(exc))], is_error=True)
        return outcome.model_dump(by_alias=True, mode="json", exclude_none=True), executed_status(outcome)

    async def _invoke(self, name: str, arguments: dict[str, Any]) -> types.CallToolResult | types.InputRequiredResult:
        """Wrap the executor: `run_coroutine_threadsafe` takes a coroutine, not any awaitable."""
        assert self._execute is not None  # `startup()` refuses to run without it
        return await self._execute(name, arguments)

    def _publish(self, task_id: str, result: dict[str, Any], status: TaskStatus, *, created_at: str | None) -> None:
        """Write the task's second ledger record to its own channel (already off the loop).

        TAG `state` is what keeps this out of the pool's own subscription, and
        `status` is the executed one rather than a hard-coded `completed`: a tool
        that asked for input has not produced a result.

        `createdAt` is echoed from the submit rather than read off the clock again,
        which is what makes this record a whole snapshot: a reader answers from it
        alone, and answers with the same `createdAt` the client's handle carries.
        """
        from rocketmq import Message  # deferred: optional dependency

        message = Message()
        message.topic = self._topic
        message.lite_topic = task_channel(task_id)
        message.tag = TAG_STATE
        message.body = json.dumps(
            {
                "taskId": task_id,
                "status": status,
                "result": result,
                "createdAt": created_at or _stamp(),
                "lastUpdatedAt": _stamp(),
            }
        ).encode("utf-8")
        self._producer.send(message)
        logger.info("task %s: %s published to %s/%s", task_id, status, self._topic, message.lite_topic)


class RocketMQTaskBackend:
    """The deployment in one process: the replica that creates tasks, and a worker pool.

    Wire the roles' clients, hand them over, and the whole deployment is one
    lifespan:

        backend = RocketMQTaskBackend(dispatcher, worker, tailer=tailer, task_tools=frozenset({"summarize"}))
        mcp = MCPServer("...", lifespan=backend.runtime, extensions=[backend.extension])

    `worker` may be `None` for a server that only creates tasks: the pool then
    runs entirely in separate `worker.py` processes under the same consumer
    group. That is the split deployment; the merged one is the default because
    the tool registry, the submit leg and the write-back leg are all one build.

    The extension is built here rather than passed in so it cannot be given a
    different dispatcher or tailer than the ones this object starts, and `execute`
    comes from `server.call_tool`: merged, the tools the pool runs and the tools the
    server lists are one registry, which is the drift the two-process split had to
    keep in sync by hand (`register_tools`).

    Shutdown order is what merging makes load-bearing, because both ends of the
    write-back now live or die together. The tailer - which is what turns a
    write-back into a recorded result - closes last, so it is still following for
    everything the worker was still running. Reverse the two and a landing result
    meets a channel nobody holds: the state is still on the broker, but this
    replica's store keeps the task at `working` until a later read tails it again.
    """

    def __init__(
        self,
        dispatcher: RocketMQTaskDispatcher,
        worker: RocketMQTaskWorker | None = None,
        *,
        tailer: RocketMQTaskTailer,
        task_tools: frozenset[str],
    ) -> None:
        self._dispatcher = dispatcher
        self._worker = worker
        self._tailer = tailer
        self.extension = Tasks(task_tools=task_tools, dispatcher=dispatcher, ledger=tailer)
        """The `Tasks` extension to register on the server (`extensions=[backend.extension]`)."""

    @asynccontextmanager
    async def runtime(self, server: MCPServer[Any]) -> AsyncIterator[dict[str, Any]]:
        """The deployment's whole lifespan, installed as `MCPServer(lifespan=...)`.

        Every client comes up before anything is served - the startups are blocking,
        so they go to a thread - and the worker's lifetime is a child of this
        object's own task group, deliberately not of the extension's:
        `Tasks.runtime` cancels its group on the way out, which would take the
        consumer down with it and put the drain after the exit that is supposed to
        follow it.
        """
        await anyio.to_thread.run_sync(self._dispatcher.startup)
        try:
            await anyio.to_thread.run_sync(self._tailer.startup)
            async with self._worker_scope(server):
                async with self.extension.runtime(server) as state:
                    yield state
        finally:
            # Last, and after the extension's runtime is gone: nothing is left to
            # record a result by the time the tailer stops delivering them, and no
            # record is left to submit once the producer closes.
            with anyio.CancelScope(shield=True):
                await anyio.to_thread.run_sync(self._tailer.shutdown)
                await anyio.to_thread.run_sync(self._dispatcher.shutdown)

    @asynccontextmanager
    async def _worker_scope(self, server: MCPServer[Any]) -> AsyncIterator[None]:
        """Bring the worker up before the extension and drain it after, or do nothing.

        The push consumer delivers on its own threads, so the pool's presence in
        this process is exactly the `_serve_pool` task: it carries no work, it
        carries the lifetime. Parented here and not in the extension's group, whose
        exit cancels its children - the worker has to be stopped by the ordered
        teardown above, not by that cancellation.

        A server without a worker (`worker=None`) just runs the extension.
        """
        if self._worker is None:
            yield
            return
        self._worker.bind_execute(server.call_tool)
        self._worker.bind_loop(asyncio.get_running_loop())
        await anyio.to_thread.run_sync(self._worker.startup)
        quiesced = anyio.Event()
        async with anyio.create_task_group() as pool:
            pool.start_soon(self._serve_pool, quiesced)
            try:
                yield
            finally:
                # Shielded: a serving process is usually cancelled rather than
                # asked to stop, and a cancelled scope refuses the thread hops
                # this order is made of.
                with anyio.CancelScope(shield=True):
                    await anyio.to_thread.run_sync(self._worker.stop_consuming)
                    await anyio.to_thread.run_sync(self._worker.wait_for_inflight)
                    await anyio.to_thread.run_sync(self._worker.close_producer)
                    quiesced.set()

    async def _serve_pool(self, quiesced: anyio.Event) -> None:
        """Hold the worker's consumer open for the server's lifetime."""
        await quiesced.wait()


def _stop(client: Any) -> None:
    """Stop a client, tolerating one that never started.

    The real client raises `IllegalStateException` from `shutdown()` when it is not
    running, and every teardown here runs in a `finally`: a startup that failed would
    otherwise be masked by the teardown that follows it, and the process would keep
    the client's threads alive on the way out.
    """
    if client is not None and getattr(client, "is_running", False):
        client.shutdown()


def _decode(message: Any, required: str) -> dict[str, Any] | None:
    """Parse a JSON message body carrying `taskId` and `required`, or `None` if it does not."""
    try:
        payload = json.loads(message.body)
    except (AttributeError, TypeError, ValueError):
        logger.warning("dropping undecodable payload; message_id: %s", getattr(message, "message_id", None))
        return None
    if not isinstance(payload, dict) or not isinstance(payload.get("taskId"), str) or required not in payload:
        logger.warning(
            "dropping payload without taskId/%s; message_id: %s", required, getattr(message, "message_id", None)
        )
        return None
    return payload


def _string(record: dict[str, Any], key: str) -> str | None:
    """One string field of a ledger record, or `None` when it is missing or not a string."""
    value = record.get(key)
    return value if isinstance(value, str) else None


def _stamp() -> str:
    """An ISO 8601 timestamp, the format `lastUpdatedAt` carries on the wire."""
    return datetime.now(timezone.utc).isoformat()


def _on_a_loop() -> bool:
    """Whether this thread is currently running an event loop (the in-process test path)."""
    try:
        asyncio.get_running_loop()
    except RuntimeError:
        return False
    return True
