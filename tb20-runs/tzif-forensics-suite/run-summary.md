# TZif Forensics Suite Run Summary

Run date: 2026-06-09 UTC

Dataset:
- Source root: `tb20-runs/tzif-forensics-suite/source`
- Delivery root: `tb20-runs/tzif-forensics-suite/delivery-opus`

Scenario:
- TZif / IANA zoneinfo binary-format forensics.
- Difficulty progression: binary header inventory, TZif v2 64-bit UTC conversion, corrupt/duplicate bundle audit plus DST local-time gap/fold classification.

Agent configuration:
- Agent: `claude-code`
- Requested Opus 4.7 model resolved from local Claude settings as `claude-opus-4-7`
- Harbor CLI: `/home/ubuntu/tb20-runtime/.venv/bin/harbor`
- Run flags: `--force-build --no-delete`

Validation:
- Oracle solution passed all three task verifiers before agent evaluation.
- Harbor stages `DEPS`, `INSPECT`, `COLLECT`, `PACKAGE`, and `AUDIT` passed.
- Agent logs were collected for all tasks.

Opus 4.7 results:

| Task | Reward | Exception | Notes |
|---|---:|---|---|
| `easy/tzif-forensics-easy` | 1 | none | Passed binary TZif v1 inventory. |
| `medium/tzif-forensics-medium` | 1 | none | Passed TZif v2 64-bit UTC conversion. |
| `hard/tzif-forensics-hard` | 0 | none | Failed verifier on corrupt-zone reason detail; execution completed normally. |

Collected log files per task:
- `agent-logs/run.json`
- `agent-logs/trajectory.json`
- `agent-logs/claude-code.txt`
- `agent-logs/verifier/reward.txt`
- `agent-logs/verifier/ctrf.json`

Production source material:
- `tb20-datasets/tzif-forensics-easy`
- `tb20-datasets/tzif-forensics-medium`
- `tb20-datasets/tzif-forensics-hard`

Source basis:
- RFC 8536 TZif format.
- IANA Time Zone Database public-domain ecosystem.
