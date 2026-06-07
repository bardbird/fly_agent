# TB2.0 Demo Contract

The client source delivery layout is:

```text
README.md
README_zh.md
easy/<task-name>/
medium/<task-name>/
hard/<task-name>/
```

Each source task must contain:

```text
task.toml
instruction.md
environment/Dockerfile
solution/solve.sh
tests/test.sh
tests/test_outputs.py
```

Production datasets do not require `agent-logs/`; those are created by execution delivery.

`task.toml` field style:

```toml
version = "1.0"

[metadata]
author_name = "..."
author_email = "..."
difficulty = "easy|medium|hard"
category = "..."
tags = ["..."]
expert_time_estimate_min = 15.0
junior_time_estimate_min = 75.0

[verifier]
timeout_sec = 900.0

[agent]
timeout_sec = 900.0

[environment]
docker_image = "tb20/<task>:latest"
build_timeout_sec = 600.0
cpus = 1
memory = "2G"
storage = "10G"
```
