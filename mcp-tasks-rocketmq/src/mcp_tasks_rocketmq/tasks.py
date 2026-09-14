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

"""Server-decided task augmentation: a long tool answers with a task handle.

The extension contributes two things and holds no server: `methods()` binds
`tasks/get`, and `intercept_tool_call` short-circuits a task-eligible
`tools/call` into a `CreateTaskResult` before the tool runs. Task storage and
execution are the extension's own - the SDK has no tasks runtime.

Storage is this replica's memory, so a `tasks/get` that lands anywhere else has
nothing to answer from - `Mcp-Name: <taskId>` affinity is what usually prevents
that. An optional `TaskLedger` is the way out of depending on it: whoever runs
the task appends its records somewhere every replica can follow, and a store miss
tails them until one lands (`mcp_tasks_rocketmq.rocketmq` is one such ledger).

Polling `tasks/get` is the only read path. Push is not a shape the SDK offers
yet: `ServerEvent` is a closed union of resource, tool and prompt events, so
`notifications/tasks` has no event to carry it.

The extension is this plugin's own: SEP-2663 moved tasks out of the core, so the
SDK ships the extension points and no tasks runtime. The MCP Python SDK's tasks
example (`examples/stories/tasks/server.py`) implements the same protocol and is
worth comparing against; `README.md` ("Following the schema") has the procedure
for moving with the spec.
"""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Awaitable, Callable, Sequence
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Protocol, TypeAlias
from uuid import uuid4

import anyio
import anyio.to_thread
from anyio.abc import TaskGroup

import mcp.types as types
from mcp.server.context import CallNext, HandlerResult, ServerRequestContext
from mcp.server.extension import Extension, MethodBinding
from mcp.server.mcpserver import MCPServer, require_client_extension
from mcp.shared.exceptions import MCPError
from mcp.types import INTERNAL_ERROR, INVALID_PARAMS, TextContent
from mcp_tasks_rocketmq.wire import (
    EXTENSION_ID,
    CreateTaskResult,
    GetTaskResult,
    TasksGetParams,
    TaskStatus,
    to_wire,
)

logger = logging.getLogger(__name__)

POLL_INTERVAL_MS = 20
"""The interval the server suggests to pollers; small enough to keep the story quick."""

READ_DEADLINE_S = 5.0
"""How long a store-missing `tasks/get` waits for the ledger's tail to push a record.

Bounded because a tail cannot answer "there is nothing": records are pushed or
they are not, so waiting is the only way to tell a task this replica has not seen
yet from one nobody ever created - and it cannot tell them apart, which is why the
answer is `INTERNAL_ERROR` rather than `INVALID_PARAMS`. The client's resolver
retries it a bounded number of times.
"""

ToolExecutor: TypeAlias = Callable[[str, dict[str, Any]], Awaitable[types.CallToolResult | types.InputRequiredResult]]
"""`MCPServer.call_tool`: run a registered tool detached from the request that asked for it."""


def _now() -> str:
    """An ISO 8601 timestamp, the format `createdAt` / `lastUpdatedAt` carry."""
    return datetime.now(timezone.utc).isoformat()


def executed_status(outcome: types.CallToolResult | types.InputRequiredResult) -> TaskStatus:
    """The status an execution that returned puts its task in.

    A tool that raised is a `completed` task carrying `isError`, so the only
    distinction left is the one the return type already makes: a tool that asked
    for input has not produced a result, and calling that `completed` would hand
    the client an `InputRequiredResult` as if it were the tool's own.
    """
    return "input_required" if isinstance(outcome, types.InputRequiredResult) else "completed"


@dataclass
class _Task:
    """One task's entire server-side state: `working` until an execution records its outcome."""

    task_id: str
    created_at: str
    last_updated_at: str
    status: TaskStatus = "working"
    result: dict[str, Any] | None = None

    def snapshot(self) -> GetTaskResult:
        """The `tasks/get` view of this task."""
        return GetTaskResult(
            task_id=self.task_id,
            status=self.status,
            created_at=self.created_at,
            last_updated_at=self.last_updated_at,
            ttl_ms=None,
            poll_interval_ms=POLL_INTERVAL_MS,
            result=self.result,
        )


class Record(Protocol):
    """Hand a task one record: a self-contained snapshot of its state.

    Every record carries the whole snapshot - status, result, and both timestamps -
    so recording one is an overwrite and never a merge: a redelivered record from
    an execution that ran twice is the same snapshot again, and a task that went
    `input_required` and back to `working` is a later record, not a contradiction.

    `created_at` and `last_updated_at` are optional because an execution recording
    its own outcome has neither: the store already holds the task it opened, and
    the clock it is recording against is this one. A ledger record carries both,
    echoed by whoever wrote it, which is what makes it self-contained.

    Async so the extension can publish the status change notification after
    recording; `status` comes from the executor rather than from the presence of a
    result, because `input_required` carries one too.
    """

    async def __call__(
        self,
        task_id: str,
        result: dict[str, Any] | None,
        *,
        status: TaskStatus,
        created_at: str | None = None,
        last_updated_at: str | None = None,
    ) -> None: ...


class TaskLedger(Protocol):
    """Where a replica that never created a task follows that task's records.

    A ledger is appended to, not queried, so reading it is subscribing the task's
    channel and waiting for a record to be pushed. `Tasks` hands the ledger the
    store's writer (`bind`), and the ledger's own delivery callback writes every
    record it receives there - the read path parses nothing and folds nothing.

    Subscribing is idempotent, because the replica that created the task is
    already following it and a reader joining the same channel must not disturb
    what that subscription has already seen. Following stops at a terminal record;
    a wait that times out stops too, or a taskId nobody ever created leaks one
    subscription per read.
    """

    def bind(self, *, record: Record) -> None:
        """Take the store's writer; called once per lifespan."""
        ...

    def subscribe(self, task_id: str) -> None:
        """Follow `task_id`'s channel. Idempotent, blocking, and may fail.

        A failure is the ledger being unavailable, not the task being absent.
        """
        ...

    def unsubscribe(self, task_id: str) -> None:
        """Stop following `task_id`'s channel. Blocking, and safe when not subscribed."""
        ...

    async def wait_until_present(self, task_id: str, *, deadline_s: float) -> None:
        """Wait until `task_id`'s records have stopped arriving, or `deadline_s` passes.

        Not "until one record has arrived": a ledger is pushed from its earliest
        record onwards, so answering at the first one would report a status the task
        has already left. What ends the wait is the ledger's own business; the store
        is the answer either way, and a wait that ran out says only that this replica
        has seen no record - never that the task does not exist.
        """
        ...


class TaskDispatcher(Protocol):
    """Where a created task actually runs.

    The extension owns task state and the `tasks/get` view; a dispatcher owns
    only execution. `bind` supplies the three things an extension cannot hold at
    construction - the server's own tool dispatch, the store's writer, and a task
    group with the server's lifetime - and `dispatch` returns as soon as execution
    is guaranteed to happen, because the requestor is still waiting for its handle.
    """

    def bind(self, *, execute: ToolExecutor, record: Record, tasks: TaskGroup) -> None:
        """Take the server-scoped collaborators; called once per lifespan."""
        ...

    async def dispatch(self, task_id: str, params: types.CallToolRequestParams, *, created_at: str) -> None:
        """Arrange for `params` to run under `task_id`, without waiting for the result.

        `created_at` is the handle's own timestamp, passed along so a dispatcher
        that writes a ledger records the same one the client was given rather than
        a second reading of the clock.
        """
        ...


class LocalDispatcher:
    """Run each task in this process, in the server's lifespan task group."""

    def __init__(self) -> None:
        self._execute: ToolExecutor | None = None
        self._record: Record | None = None
        self._tasks: TaskGroup | None = None

    def bind(self, *, execute: ToolExecutor, record: Record, tasks: TaskGroup) -> None:
        self._execute, self._record, self._tasks = execute, record, tasks

    async def dispatch(self, task_id: str, params: types.CallToolRequestParams, *, created_at: str) -> None:
        assert self._tasks is not None  # `Tasks` refuses to create a task before the lifespan binds
        self._tasks.start_soon(self._run, task_id, params)

    async def _run(self, task_id: str, params: types.CallToolRequestParams) -> None:
        """Run the tool detached from its request and record the result.

        The `ctx` the interceptor saw belongs to that one `tools/call`, so the
        work goes through the server's own dispatch rather than re-entering
        `call_next` with a context that is already answered.
        """
        assert self._execute is not None and self._record is not None
        try:
            outcome = await self._execute(params.name, params.arguments or {})
        except Exception as exc:  # boundary: a raise here would tear down the runtime's task group
            # The same conversion the ordinary `tools/call` path applies: a tool
            # that fails is a task that completed, carrying an `isError` result.
            logger.exception("task %s raised while running tool %r", task_id, params.name)
            outcome = types.CallToolResult(content=[TextContent(type="text", text=str(exc))], is_error=True)
        result = outcome.model_dump(by_alias=True, mode="json", exclude_none=True)
        await self._record(task_id, result, status=executed_status(outcome))


class Tasks(Extension):
    """The `io.modelcontextprotocol/tasks` server half, for the tools named in `task_tools`.

    `settings()` is left at its default empty dict: the extension's capability
    entry is `Record<string, never>` - support, no settings. `dispatcher` decides
    where task execution happens; the default runs it in this process. `ledger`,
    when given, is what a `tasks/get` falls back to on a store miss: it tails the
    task's own records into this store, which is what makes any replica able to
    answer one.
    """

    identifier = EXTENSION_ID

    def __init__(
        self,
        *,
        task_tools: frozenset[str],
        dispatcher: TaskDispatcher | None = None,
        ledger: TaskLedger | None = None,
    ) -> None:
        self._task_tools = task_tools
        self._dispatcher: TaskDispatcher = dispatcher if dispatcher is not None else LocalDispatcher()
        self._ledger = ledger
        self._store: dict[str, _Task] = {}
        self._bound = False

    @asynccontextmanager
    async def runtime(self, server: MCPServer[Any]) -> AsyncIterator[dict[str, Any]]:
        """The execution seam, installed as `MCPServer(lifespan=tasks.runtime)`.

        Task execution outlives the request that created it, so the dispatcher
        needs a task group with the server's lifetime; a server with its own
        lifespan nests this one inside it. The ledger gets the same store writer,
        since what it receives is a record of the same kind the dispatcher's
        execution produces. Leaving cancels whatever is still in flight - a task
        does not survive the process that owns its store.
        """
        async with anyio.create_task_group() as tg:
            self._dispatcher.bind(execute=server.call_tool, record=self._record, tasks=tg)
            if self._ledger is not None:
                self._ledger.bind(record=self._record)
            self._bound = True
            try:
                yield {}
            finally:
                self._bound = False
                tg.cancel_scope.cancel()

    async def _record(
        self,
        task_id: str,
        result: dict[str, Any] | None,
        *,
        status: TaskStatus,
        created_at: str | None = None,
        last_updated_at: str | None = None,
    ) -> None:
        """Put one record in the store, replacing what was there.

        An overwrite, not a merge, because a record is a whole snapshot: whoever
        wrote it is the authority on the task's state, and a store that held on to
        an earlier one would keep answering with a state the task has left. Nothing
        freezes at a terminal status either - `input_required` going back to
        `working` is a step the protocol allows, and it arrives as a later record.

        A record repeating what is already stored (at-least-once delivery of an
        execution that ran twice) replaces it with the same snapshot.
        """
        previous = self._store.get(task_id)
        task = _Task(
            task_id=task_id,
            created_at=created_at or (previous.created_at if previous is not None else None) or _now(),
            last_updated_at=last_updated_at or _now(),
            status=status,
            result=result,
        )
        self._store[task_id] = task

    def methods(self) -> Sequence[MethodBinding]:
        """`tasks/get`. `tasks/update` and `tasks/cancel` belong to the full state machine."""
        return [MethodBinding("tasks/get", TasksGetParams, self.get)]

    async def get(self, ctx: ServerRequestContext[Any, Any], params: TasksGetParams) -> HandlerResult:
        """Read one task, from this replica's store or from the ledger behind it.

        A pure idempotent read: `tasks/get` writes no state of its own. Following a
        task's ledger on a store miss does subscribe, which is why the first read of
        a task costs more than the rest - after it, records are pushed into the store
        and every poll is a local hit, including the ones after a fallback.

        Raises:
            MCPError: `INVALID_PARAMS` when no task holds that id and there is no
                ledger to ask; `INTERNAL_ERROR` when there is one and it could not
                answer, since a tail that pushed nothing says the state is unknown,
                not that the task never existed.
        """
        require_client_extension(ctx, EXTENSION_ID)
        task = self._store.get(params.task_id) or await self._from_ledger(params.task_id)
        if task is None:
            raise MCPError(code=INVALID_PARAMS, message=f"unknown task {params.task_id!r}")
        return to_wire(task.snapshot())

    async def _from_ledger(self, task_id: str) -> _Task | None:
        """Tail the ledger of a task this replica never created, until a record lands.

        The ledger writes what it receives into this store, so the wait is for the
        records to stop arriving and the answer is then an ordinary store read: this
        path parses nothing and folds nothing. Subscribing is what makes it work at
        all, and it is idempotent - the replica that created the task is already
        following it, so joining costs one call and resets no offset.

        A subscribe that failed and a wait that stored nothing are both
        `INTERNAL_ERROR`, and both drop the subscription. A taskId nobody ever
        created never produces a terminal record to unsubscribe on, so keeping the
        tail would leak one per read - three per read, once the client's bounded
        retry is counted. The store is what settles it rather than the wait: a
        terminal record lands and drops its own tail in the same step, so the
        snapshot can be there with nothing left to wait on.

        Raises:
            MCPError: `INTERNAL_ERROR` when the task's state could not be read.
        """
        if self._ledger is None:
            return None
        try:
            await anyio.to_thread.run_sync(self._ledger.subscribe, task_id)
        except Exception as exc:  # boundary: a channel that cannot be subscribed is an unavailable ledger
            logger.warning("task %s: ledger cannot be subscribed: %s", task_id, exc)
            raise MCPError(code=INTERNAL_ERROR, message=f"task {task_id!r} cannot be read right now") from exc
        await self._ledger.wait_until_present(task_id, deadline_s=READ_DEADLINE_S)
        task = self._store.get(task_id)
        if task is None:
            logger.warning("task %s: no ledger record within %ss", task_id, READ_DEADLINE_S)
            await anyio.to_thread.run_sync(self._ledger.unsubscribe, task_id)
            raise MCPError(code=INTERNAL_ERROR, message=f"task {task_id!r} cannot be read right now")
        return task

    async def intercept_tool_call(
        self,
        params: types.CallToolRequestParams,
        ctx: ServerRequestContext[Any, Any],
        call_next: CallNext,
    ) -> HandlerResult:
        """Answer a task-eligible call with a handle; pass everything else through.

        The decision is the server's alone - the client only declared that it
        understands the envelope, which `require_client_extension` enforces. The
        task is stored and dispatch is confirmed before the handle is returned:
        a client that receives a `taskId` can always read it back, and its work
        is guaranteed to run.

        Raises:
            RuntimeError: `runtime` was never installed as the server lifespan,
                so nothing can execute the task.
        """
        if params.name not in self._task_tools:
            return await call_next(ctx)
        require_client_extension(ctx, EXTENSION_ID)
        if not self._bound:
            raise RuntimeError(f"{type(self).__name__}.runtime must be installed as the server lifespan")
        now = _now()
        task = _Task(task_id=uuid4().hex, created_at=now, last_updated_at=now)
        self._store[task.task_id] = task
        await self._dispatcher.dispatch(task.task_id, params, created_at=task.created_at)
        return to_wire(
            CreateTaskResult(
                task_id=task.task_id,
                status="working",
                created_at=task.created_at,
                last_updated_at=task.last_updated_at,
                ttl_ms=None,
                poll_interval_ms=POLL_INTERVAL_MS,
            )
        )
