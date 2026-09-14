# examples

| example | what it shows | needs a broker |
|---|---|---|
| [`offline_walkthrough.py`](offline_walkthrough.py) | the whole lifecycle against an in-memory stand-in for the client: inline vs task-eligible tools, one `command` and one `state` message, and a second replica reading the ledger | **no** |
| [`merged_deployment.py`](merged_deployment.py) | all three roles in one process - worker, creating replica, client - and four steps of real messaging, timestamped | yes |

[`demo_tools.py`](demo_tools.py) holds the tools both of them register, and is what
`--tools demo_tools:demo_tools` loads.

Start with `offline_walkthrough.py` - it runs the real dispatcher, worker, tailer
and extension, so what it prints is the mechanism rather than a mock-up, and it
needs nothing installed beyond the package:

```bash
python examples/offline_walkthrough.py
```

`merged_deployment.py` reads the environment (`RMQ_ENDPOINTS` required; see the
top-level README for the full list). The split deployment needs no separate
example: it is `build_server(config, with_worker=False)` plus
`python -m mcp_tasks_rocketmq.worker` processes.
