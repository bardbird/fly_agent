# TZif Forensics Suite Run Summary

Run date: 2026-06-09 UTC

Dataset:
- Source root: `tb20-runs/tzif-forensics-suite/source`
- Final delivery root: `tb20-runs/tzif-forensics-suite/delivery-hardfix`

Scenario:
- TZif / IANA zoneinfo binary-format forensics.
- Difficulty progression: binary header inventory, TZif v2 64-bit UTC conversion, and zone bundle audit with corrupt-file handling plus DST gap/fold classification.

Verifier micro-adjustment:
- The hard verifier was relaxed for fields that were underspecified in `instruction.md`:
  - `invalid_zones[].reason` now checks that the corrupt file is identified and the reason indicates truncation, instead of requiring one exact internal parser string.
  - `offsets` accepts both offset seconds (`-18000`) and ISO-style offsets (`-05:00`) because the hard instruction did not mandate a unit or rendering format.
- Core requirements remain checked: corrupt-zone detection, duplicate grouping, version counts, candidate UTC values, gap/fold classification, CSV/JSON shape, sort order, and trailing newlines.

Agent configuration:
- Agent: `claude-code`
- Model: `claude-opus-4-7` from local Claude Code Opus 4.7 configuration
- Harbor CLI: `/home/ubuntu/tb20-runtime/.venv/bin/harbor`
- Run flags: `--force-build --no-delete`

Validation:
- Oracle solution passes all three task verifiers.
- Harbor stages `DEPS`, `INSPECT`, `COLLECT`, `PACKAGE`, and `AUDIT` passed.
- Agent logs are collected for all tasks.

Opus 4.7 results:

| Task | Reward | Exception |
|---|---:|---|
| `easy/tzif-forensics-easy` | 1 | none |
| `medium/tzif-forensics-medium` | 1 | none |
| `hard/tzif-forensics-hard` | 1 | none |

Collected log files per task:
- `agent-logs/run.json`
- `agent-logs/trajectory.json`
- `agent-logs/claude-code.txt`
- `agent-logs/verifier/reward.txt`
- `agent-logs/verifier/ctrf.json`
