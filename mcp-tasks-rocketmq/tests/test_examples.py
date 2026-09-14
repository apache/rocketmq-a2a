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

"""The offline example, run as a test, so it cannot rot unnoticed.

`offline_walkthrough.py` is the one example a reader can run without provisioning
anything, which makes it the one most likely to be trusted and the one worth
gating. Run as a subprocess rather than imported: what is asserted is that the
documented command works, entry point and all.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

EXAMPLE = Path(__file__).parent.parent / "examples" / "offline_walkthrough.py"


def test_the_offline_walkthrough_runs_and_narrates_the_whole_lifecycle() -> None:
    """Both acts, end to end, on no broker at all."""
    result = subprocess.run([sys.executable, str(EXAMPLE)], capture_output=True, text=True, timeout=120)
    assert result.returncode == 0, result.stderr
    out = result.stdout

    # Act 1: an inline tool touches nothing, a task-eligible one rides one message.
    assert "broker untouched (0 messages sent)" in out
    assert "tag='command'" in out
    assert "status='working' (the command is still queued)" in out

    # The pool executes it and the terminal record lands in the creating replica's store.
    assert "status='completed' result={'words': 10, 'unique': 10}" in out
    assert "seq=0 tag='command' status='working'" in out
    assert "seq=1 tag='state' status='completed'" in out

    # Act 2: a replica that created nothing answers from the ledger.
    assert "ACT 2" in out
    assert out.count("status='completed'") >= 3
    assert "2 messages on 'mcp-tasks', no broker involved" in out
