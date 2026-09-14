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

"""RocketMQ deployment configuration, in one place.

`RocketMQConfig` holds everything the wiring needs. It is pure data: no `mcp`
and no `rocketmq` client is imported until a build function runs.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any
from uuid import uuid4


@dataclass(frozen=True)
class RocketMQConfig:
    """Everything the deployment's RocketMQ wiring needs.

    Every `build_*` function in `server.py` takes an instance; `from_env()`
    reads the `RMQ_*` variables into one. Embedders construct it directly.
    """

    endpoints: str
    """Broker address (`RMQ_ENDPOINTS`); the only field with no default."""

    access_key: str = ""
    """`RMQ_AK` - empty means the client's no-auth path."""

    secret_key: str = ""
    """`RMQ_SK`, paired with `access_key`."""

    namespace: str = ""
    """`RMQ_NAMESPACE` - the instance's namespace, empty when there is none."""

    tasks_topic: str = "mcp-tasks"
    """The one topic the whole lifecycle rides (`RMQ_TASKS_TOPIC`).

    Needs the LiteTopic attribute on the instance (RocketMQ 5.5.0 or later): it is
    the parent of the `t-<taskId>` channels as well as the topic the pool
    subscribes.
    """

    worker_group: str = "mcp-tasks-workers"
    """The pool's plain consumer group (`RMQ_WORKER_GROUP`).

    Shared by the whole pool on purpose: submitted work is a queue, so exactly
    one worker must get each task - whether it runs in the server process or a
    `worker.py` one.
    """

    tailer_group_prefix: str = "mcp-tasks-tailer"
    """Prefix of this replica's own lite group (`RMQ_TAILER_GROUP_PREFIX`)."""

    replica_id: str | None = None
    """Stable per-replica id (`RMQ_REPLICA_ID`); a per-process random one when unset.

    One group per replica is not a naming preference. A lite subscription's
    offset is per (group, channel), so two replicas following one task under a
    shared group would take each other's records and reset each other's position
    on it. Keeping the id stable across restarts keeps the offsets stable too;
    without it each incarnation starts fresh, leaving the previous offsets behind.
    """

    _replica_suffix: str = field(default_factory=lambda: uuid4().hex[:8], init=False)
    """The per-instance fallback id, generated once so `tailer_group()` is stable."""

    @classmethod
    def from_env(cls) -> "RocketMQConfig":
        """Read the `RMQ_*` variables; `endpoints` is required and checked here."""
        return cls(
            endpoints=os.environ["RMQ_ENDPOINTS"],
            access_key=os.environ.get("RMQ_AK", ""),
            secret_key=os.environ.get("RMQ_SK", ""),
            namespace=os.environ.get("RMQ_NAMESPACE", ""),
            tasks_topic=os.environ.get("RMQ_TASKS_TOPIC", "mcp-tasks"),
            worker_group=os.environ.get("RMQ_WORKER_GROUP", "mcp-tasks-workers"),
            tailer_group_prefix=os.environ.get("RMQ_TAILER_GROUP_PREFIX", "mcp-tasks-tailer"),
            replica_id=os.environ.get("RMQ_REPLICA_ID"),
        )

    def client_configuration(self) -> Any:
        """A rocketmq `ClientConfiguration` built from this config."""
        from rocketmq import ClientConfiguration, Credentials

        credentials = Credentials(self.access_key, self.secret_key)
        return ClientConfiguration(self.endpoints, credentials, namespace=self.namespace)

    def tailer_group(self) -> str:
        """This replica's lite group: the prefix plus its replica id."""
        return f"{self.tailer_group_prefix}-{self.replica_id or self._replica_suffix}"
