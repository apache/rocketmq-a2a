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

"""SEP-2663 wire shapes, translated from the `ext-tasks` schema (the MVP subset).

`io.modelcontextprotocol/tasks` is defined by its own schema repository
([ext-tasks](https://github.com/modelcontextprotocol/ext-tasks),
`schema/draft/schema.ts`), which is what these shapes answer to. `mcp_types` is
not: the `CreateTaskResult` it carries is the 2025-11-25 in-core design
(`{task: Task}`, and marked "2025-11-25 only"), wire-incompatible with the flat
extension shape below - SEP-2663 moved tasks out of the core and into the
extension, so the core types describe the version that design replaced. Both
halves of the plugin import this module so the two translations cannot drift; in
a real deployment each side owns its own.

Translated here: the `Task` fields, the flat `CreateTaskResult`, and the
`tasks/get` pair. `tasks/update`, `tasks/cancel`, the five `DetailedTask`
variants and `notifications/tasks` are outside the MVP - see the README.

The MCP Python SDK's tasks example (`examples/stories/tasks/tasks_wire.py`) is a
second translation of the same schema, useful for comparing against but not the
authority; `README.md` ("Following the schema") has the procedure for moving with
either.
"""

from typing import Any, Literal, get_args

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

import mcp.types as types

EXTENSION_ID = "io.modelcontextprotocol/tasks"
"""The extension identifier both halves advertise; its capability entry is the empty object."""

TaskStatus = Literal["working", "input_required", "completed", "failed", "cancelled"]
"""The schema's five statuses. This MVP emits `working`, `completed` and `input_required`."""

TASK_STATUSES: frozenset[TaskStatus] = frozenset(get_args(TaskStatus))
"""The statuses a reader understands: a record carrying anything else is skipped, not fatal.

A writer ahead of this reader must not make a task unreadable, so the set is what
validates a record rather than a raise.
"""

TERMINAL_STATUSES: frozenset[TaskStatus] = frozenset({"completed", "failed", "cancelled"})
"""The statuses the state machine cannot leave, and so the only ones worth stopping a tail for.

`input_required` is not one of them: `tasks/update` moves a task out of it and back
to `working`, so a reader that let go of the channel there would never see the
fallback the protocol allows.
"""


class Task(BaseModel):
    """The fields every task-bearing shape carries (`Task` in the schema).

    Not a result on its own: `CreateTaskResult` and `GetTaskResult` mix it into
    `Result`, matching the schema's flat `Result & Task` intersections.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    task_id: str
    """The task identifier. Rides the `Mcp-Name` header on every `tasks/*` request."""

    status: TaskStatus

    created_at: str
    """ISO 8601 timestamp when the task was created."""

    last_updated_at: str
    """ISO 8601 timestamp when the task was last updated."""

    ttl_ms: int | None
    """Retention from creation in milliseconds, null for unlimited. Required and nullable."""

    poll_interval_ms: int | None = None
    """Polling interval the server suggests; clients SHOULD honour it."""


class CreateTaskResult(types.Result, Task):
    """A `tools/call` answered with a task handle instead of a result.

    `Result & Task & {resultType: "task"}` - flat, so `taskId` and `status` sit
    at the top level. `result_type` is what the client's `ResultClaim` keys on.
    """

    result_type: Literal["task"] = "task"


class TasksGetParams(types.RequestParams):
    """`tasks/get` params: the task to read, and nothing else (the read is pure)."""

    task_id: str


class TasksGetRequest(types.Request[TasksGetParams, Literal["tasks/get"]]):
    """`tasks/get`. `name_param` puts `taskId` in `Mcp-Name` for load-balancer affinity."""

    method: Literal["tasks/get"] = "tasks/get"
    params: TasksGetParams
    name_param = "taskId"


class GetTaskResult(types.Result, Task):
    """The `tasks/get` response: `Result & DetailedTask & {resultType: "complete"}`.

    `tasks/get` answers with the ordinary result tag, not `"task"` - the handle
    is what carried `"task"`. The MVP flattens the five `DetailedTask` variants
    into one shape with an optional `result`, populated once `status` is
    `completed`; the variant union is part of the full five-state build.
    """

    result_type: Literal["complete"] = "complete"

    result: dict[str, Any] | None = None
    """The original request's result shape (here a `CallToolResult`), once completed."""


def to_wire(result: CreateTaskResult | GetTaskResult) -> dict[str, Any]:
    """Dump a task-bearing result for the runner, keeping `ttlMs`'s explicit null.

    The runner dumps a returned `BaseModel` with `exclude_none=True`, which would
    drop the required-and-nullable `ttlMs` entirely; a handler-returned dict
    reaches the wire as-is. Genuinely optional fields (`pollIntervalMs`,
    `result`) stay absent when unset.
    """
    wire = result.model_dump(by_alias=True, mode="json", exclude_none=True)
    wire.setdefault("ttlMs", None)
    return wire
