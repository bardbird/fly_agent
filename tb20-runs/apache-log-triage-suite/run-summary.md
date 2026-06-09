# Apache Log Triage Suite Run Summary

Run date: 2026-06-09 UTC

Dataset root:
- `tb20-runs/apache-log-triage-suite/source`

Delivery root:
- `tb20-runs/apache-log-triage-suite/delivery`

Agent configuration:
- Agent: `claude-code`
- Requested model: `opus4.7`
- Harbor CLI: `/home/ubuntu/tb20-runtime/.venv/bin/harbor`

Execution notes:
- `DEPS`, `INSPECT`, `COLLECT`, `PACKAGE`, and `AUDIT` passed.
- Harbor was run with `--force-build --no-delete` so each task environment was built from its Dockerfile and trial logs were retained.
- Agent logs were collected for all three tasks under each task's `agent-logs/` directory.

Results:

| Task | Reward | Exception |
|---|---:|---|
| `easy/apache-log-triage-easy` | 0 | `NonZeroAgentExitCodeError` |
| `medium/apache-log-triage-medium` | 0 | `NonZeroAgentExitCodeError` |
| `hard/apache-log-triage-hard` | 0 | `NonZeroAgentExitCodeError` |

Root cause:
- The local Claude Code provider rejected `opus4.7`.
- The captured agent log reports: `API Error: 400 The supported API model names are deepseek-v4-pro or deepseek-v4-flash, but you passed opus4.7.`

Collected log files per task:
- `agent-logs/run.json`
- `agent-logs/trajectory.json`
- `agent-logs/claude-code.txt`
- `agent-logs/verifier/reward.txt`
- `agent-logs/verifier/ctrf.json`
