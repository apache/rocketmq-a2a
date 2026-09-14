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

"""The client half (`mcp_tasks_rocketmq.client`): a handle resolved into a result.

Two levels. `TasksClient` against a real server proves the transparent path - a
task-eligible `tools/call` returns the tool's own result, with the handle and the
polling invisible to the caller. Against a stub session it proves what the
resolver does with answers a server cannot be made to produce on demand: a read
that failed recoverably, one that failed for good, and a status this build never
writes.

The stub is a session and nothing else, because `ClaimContext` is only ever asked
for one: the resolver's whole contract is the requests it sends and what it does
with each answer.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import anyio
import pytest
from mcp_tasks_rocketmq.client import TasksClient
from mcp_tasks_rocketmq.wire import CreateTaskResult, GetTaskResult

from mcp.shared.exceptions import MCPError
from mcp.types import INTERNAL_ERROR, INVALID_PARAMS, METHOD_NOT_FOUND, CallToolResult, TextContent

pytestmark = pytest.mark.anyio

CREATED_AT = "2026-01-01T00:00:00+00:00"


def _handle(task_id: str = "t-1") -> CreateTaskResult:
    """The handle a resolver is handed, polling as fast as the tests need."""
    return CreateTaskResult(
        task_id=task_id,
        status="working",
        created_at=CREATED_AT,
        last_updated_at=CREATED_AT,
        ttl_ms=None,
        poll_interval_ms=1,
    )


def _snapshot(status: str, result: dict[str, Any] | None = None) -> GetTaskResult:
    return GetTaskResult(
        task_id="t-1",
        status=status,  # pyright: ignore[reportArgumentType] - the tests name statuses directly
        created_at=CREATED_AT,
        last_updated_at=CREATED_AT,
        ttl_ms=None,
        poll_interval_ms=1,
        result=result,
    )


def _completed(text: str = "done") -> GetTaskResult:
    outcome = CallToolResult(content=[TextContent(type="text", text=text)])
    return _snapshot("completed", outcome.model_dump(by_alias=True, mode="json", exclude_none=True))


class _StubSession:
    """Answers `send_request` from a script; an `Exception` in it is raised instead."""

    def __init__(self, *answers: GetTaskResult | Exception) -> None:
        self._answers = list(answers)
        self.sent = 0

    async def send_request(self, request: Any, result_type: Any) -> Any:
        self.sent += 1
        answer = self._answers.pop(0)
        if isinstance(answer, Exception):
            raise answer
        return answer


@dataclass
class _StubContext:
    """The one thing `ClaimContext` is asked for."""

    session: _StubSession


async def _resolve(client: TasksClient, session: _StubSession) -> CallToolResult:
    return await client._await_task(_handle(), _StubContext(session))  # pyright: ignore[reportPrivateUsage, reportArgumentType]


async def test_a_handle_is_resolved_into_the_tools_own_result() -> None:
    """The transparent path: one poll, and the caller sees a `CallToolResult`."""
    session = _StubSession(_completed("summary"))
    result = await _resolve(TasksClient(), session)
    assert isinstance(result, CallToolResult)
    assert result.content[0].text == "summary"  # pyright: ignore[reportAttributeAccessIssue]
    assert session.sent == 1


async def test_a_task_still_working_is_polled_until_it_finishes() -> None:
    """`working` is not an answer, so the resolver asks again."""
    session = _StubSession(_snapshot("working"), _snapshot("working"), _completed())
    await _resolve(TasksClient(), session)
    assert session.sent == 3


@pytest.mark.parametrize("code", [INVALID_PARAMS, INTERNAL_ERROR])
async def test_a_recoverable_read_failure_is_absorbed(code: int) -> None:
    """A replica that cannot answer yet is not the task being gone.

    Both codes mean "this replica has no state for that id" - one because its view
    has not caught up, one because its read path is failing - and a handle the
    server already handed out survives either.
    """
    session = _StubSession(MCPError(code=code, message="not here"), _completed())
    await _resolve(TasksClient(), session)
    assert session.sent == 2


async def test_the_retries_are_bounded() -> None:
    """Consecutive failures past the budget surface, rather than polling to the timeout."""
    failures = [MCPError(code=INTERNAL_ERROR, message="cannot read") for _ in range(4)]
    session = _StubSession(*failures)
    with pytest.raises(MCPError) as raised:
        await _resolve(TasksClient(retries=2), session)
    assert raised.value.code == INTERNAL_ERROR
    assert session.sent == 3  # the first read plus the two retries it was allowed


async def test_a_successful_read_resets_the_budget() -> None:
    """The budget counts consecutive failures: a task that answers once is not one failure closer to giving up."""
    session = _StubSession(
        MCPError(code=INTERNAL_ERROR, message="cannot read"),
        _snapshot("working"),
        MCPError(code=INTERNAL_ERROR, message="cannot read"),
        _completed(),
    )
    await _resolve(TasksClient(retries=1), session)
    assert session.sent == 4


async def test_an_answer_the_server_settled_on_is_not_retried() -> None:
    """Anything outside the two recoverable codes is the server's final word."""
    session = _StubSession(MCPError(code=METHOD_NOT_FOUND, message="no tasks/get here"))
    with pytest.raises(MCPError) as raised:
        await _resolve(TasksClient(), session)
    assert raised.value.code == METHOD_NOT_FOUND
    assert session.sent == 1


@pytest.mark.parametrize("status", ["input_required", "failed", "cancelled"])
async def test_a_status_this_build_never_writes_is_refused(status: str) -> None:
    """The resolver finishes `completed` tasks; the other terminals need the full state machine."""
    session = _StubSession(_snapshot(status))
    with pytest.raises(RuntimeError, match=status):
        await _resolve(TasksClient(), session)


async def test_a_task_that_never_finishes_times_out() -> None:
    """Bounded, so a caller waiting on a resolved result cannot wait forever."""
    session = _StubSession(*[_snapshot("working") for _ in range(1000)])
    with anyio.fail_after(5):
        with pytest.raises(TimeoutError):
            await _resolve(TasksClient(timeout_s=0.05), session)
