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

"""Call a long tool, get a task handle, poll it to completion - transparently.

One `ResultClaim` is the whole client half: it tells the session that
`resultType: "task"` is a shape this client understands, and its resolver turns
the handle into the `CallToolResult` the caller is waiting for. So
`client.call_tool` returns a finished result either way, and whether the server
chose the task path is invisible to the caller. `session.call_tool(...,
allow_claimed=True)` opts out and hands back the raw handle, for a caller that
wants to observe the task's states itself.

A client that declares nothing is refused by the server
(`require_client_extension`); one that declares the extension without this claim
gets a validation error on the handle it cannot parse, so both halves of the
declaration are required.

The resolver retries a poll that failed a bounded number of times. A handle the
server has already handed out is not made invalid by one bad read: on a
load-balanced deployment a `tasks/get` can land on a replica that cannot see the
task yet, or on one whose own read path is momentarily down, and both are
recoverable in a way "this taskId does not exist" is not.
"""

from collections.abc import Sequence
from typing import Any

import anyio

import mcp.types as types
from mcp.client import ClaimContext, ClientExtension, ResultClaim
from mcp.shared.exceptions import MCPError
from mcp.types import INTERNAL_ERROR, INVALID_PARAMS
from mcp_tasks_rocketmq.wire import (
    EXTENSION_ID,
    CreateTaskResult,
    GetTaskResult,
    TasksGetParams,
    TasksGetRequest,
)

RESOLVE_TIMEOUT_S = 30.0
"""Default: how long the resolver waits for a terminal status before giving up."""

FALLBACK_POLL_INTERVAL_MS = 1000
"""Default, used when the server suggests no `pollIntervalMs`; the field is optional."""

POLL_RETRIES = 3
"""Default: how many consecutive failed polls the resolver absorbs before giving up.

Bounded, and reset by any successful read: a task that really does not exist must
still surface as an error rather than being polled until the timeout.
"""

_RETRYABLE = frozenset({INVALID_PARAMS, INTERNAL_ERROR})
"""The two answers that may not be final: "no such task" and "cannot read it".

`INVALID_PARAMS` covers a replica whose view of the task has not caught up;
`INTERNAL_ERROR` covers one whose read path is failing. Anything else - a refused
capability, a rejected request - is the server's settled answer.
"""


class TasksClient(ClientExtension):
    """The `io.modelcontextprotocol/tasks` client half: claim the shape, then finish it.

    The three knobs are the deployment's, not the protocol's: how long a task may
    take, how many unreadable answers to absorb, and how often to ask when the
    server suggests nothing.
    """

    identifier = EXTENSION_ID

    def __init__(
        self,
        *,
        timeout_s: float = RESOLVE_TIMEOUT_S,
        retries: int = POLL_RETRIES,
        fallback_poll_interval_ms: int = FALLBACK_POLL_INTERVAL_MS,
    ) -> None:
        self._timeout_s = timeout_s
        self._retries = retries
        self._fallback_poll_interval_ms = fallback_poll_interval_ms

    def claims(self) -> Sequence[ResultClaim[Any]]:
        """One claim on `tools/call`. The SDK advertises the extension for it and routes on the tag."""
        return [ResultClaim(result_type="task", model=CreateTaskResult, resolve=self._await_task)]

    async def _await_task(self, claimed: CreateTaskResult, ctx: ClaimContext) -> types.CallToolResult:
        """Poll `tasks/get` until the task completes, then return the result it carries.

        Polling is the protocol's baseline path: `notifications/tasks` only ever
        accelerates it, so a resolver built this way is already correct.

        Raises:
            MCPError: `retries` consecutive reads failed the same recoverable way,
                or one failed in a way that cannot be retried.
            RuntimeError: The task reached a terminal status this MVP server never
                produces (`input_required`, `failed`, `cancelled`).
            TimeoutError: No terminal status within `timeout_s`.
        """
        request = TasksGetRequest(params=TasksGetParams(task_id=claimed.task_id))
        interval = (claimed.poll_interval_ms or self._fallback_poll_interval_ms) / 1000
        failures = 0
        with anyio.fail_after(self._timeout_s):
            while True:
                try:
                    task = await ctx.session.send_request(request, GetTaskResult)
                except MCPError as exc:
                    failures += 1
                    if exc.code not in _RETRYABLE or failures > self._retries:
                        raise
                    # Back off with the attempt: whatever this replica is missing,
                    # asking again immediately is the least likely thing to fix it.
                    await anyio.sleep(interval * failures)
                    continue
                failures = 0
                if task.status == "completed":
                    # `result` is the original request's own result shape.
                    return types.CallToolResult.model_validate(task.result)
                if task.status != "working":
                    raise RuntimeError(f"task {claimed.task_id} is {task.status}, which this client cannot finish")
                await anyio.sleep(interval)
