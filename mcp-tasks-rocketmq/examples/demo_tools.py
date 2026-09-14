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

"""The entry point loads tools with `--tools module:attr`, so the demo tools
live here rather than in the plugin: `load_tools("demo_tools:demo_tools")`
returns the register function and the task-tool names the entry points need.

Run the merged deployment with them (this directory has to be importable):

    RMQ_ENDPOINTS=... PYTHONPATH=examples python -m mcp_tasks_rocketmq \\
        --tools demo_tools:demo_tools --http
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Callable
from typing import Any

import anyio

from mcp.server.mcpserver import MCPServer

BASE_WORK_S = 0.05
"""The floor of `word_count`'s pretend work, before the per-character part."""

MAX_WORK_S = 3.0
"""The ceiling, so a large corpus does not make the demo look hung."""


def demo_tools() -> tuple[Callable[[MCPServer[Any]], None], frozenset[str]]:
    """Register the demo tools; `word_count` is the one that runs as a task."""

    def register(mcp: MCPServer[Any]) -> None:
        @mcp.tool()
        async def word_count(corpus: str) -> dict[str, Any]:
            """Count words: totals, sentences, unique words, and the top terms.

            Slow for long corpora - about a second per thousand characters - so a
            real document runs as a task on a worker.
            """
            await anyio.sleep(min(BASE_WORK_S + len(corpus) / 1000, MAX_WORK_S))
            words = re.findall(r"[a-z']+", corpus.lower())
            sentences = [s for s in re.split(r"[.!?]+", corpus) if s.strip()]
            freq = Counter(words)
            return {
                "words": len(words),
                "sentences": len(sentences) or (1 if corpus.strip() else 0),
                "uniqueWords": len(freq),
                "topTerms": [term for term, _ in freq.most_common(5)],
            }

        @mcp.tool()
        def char_count(corpus: str) -> int:
            """Count characters. Fast, so the creating replica answers it inline."""
            return len(corpus)

    return register, frozenset({"word_count"})
