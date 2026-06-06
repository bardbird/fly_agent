---
name: tb20-production-pipeline
description: Use this skill for Terminal-Bench 2.0 task production workflows, including task design, scaffold creation, reference solution construction, verifier construction, real artifact inspection, strict stage-gated validation, and standardized delivery packaging. This skill is filesystem- and script-driven, independent of backend services or databases; never mock rewards, test reports, trajectories, verifier results, or delivery evidence.
---

# TB 2.0 Production Pipeline

This skill runs Terminal-Bench 2.0 production as a Codex workflow. Codex may edit files and use AI reasoning, but every stage gate must be enforced by scripts and real filesystem artifacts.

## Non-Negotiable Rules

- Do not use backend APIs, databases, UI state, or in-memory assumptions as source of truth.
- Source of truth is the task directory plus evidence files under the workflow workspace.
- Do not fabricate `reward.txt`, `ctrf.json`, `trajectory.json`, `run.json`, Docker status, Harbor status, or verifier output.
- If a stage lacks real evidence, mark it blocked with `scripts/tb20_flow.py record --status BLOCKED`.
- Any PASS stage must reference an existing evidence file.
- Use scripts before summarizing status to the user.

## Default Paths

- Harbor: `/Users/liuyifei/Liu/hub/harbor`
- Terminal-Bench: `/Users/liuyifei/Liu/hub/terminal-bench-main`
- Demo data: `/Users/liuyifei/Downloads/terminal_bench_2.0_demo_20260528`

Override these when the user provides different paths.

## Required Task Layout

Required files:

```text
task.toml
instruction.md
environment/Dockerfile
solution/solve.sh
tests/test.sh
tests/test_outputs.py
```

Enhanced delivery logs:

```text
agent-logs/run.json
agent-logs/trajectory.json
agent-logs/verifier/ctrf.json
agent-logs/verifier/reward.txt
```

## Workflow Workspace

For every run, create a workspace that stores state and evidence:

```bash
SKILL_DIR=/path/to/tb20-production-pipeline

python3 "$SKILL_DIR/scripts/tb20_flow.py" init \
  --workspace <workspace> \
  --source-root <tb20_dataset_root> \
  --output-root <delivery_output_root>
```

This creates:

```text
<workspace>/
├── state.json
└── evidence/
```

## Stage Gates

Run stages in order. Do not skip a gate.

### 1. Dependency Check

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" deps --workspace <workspace>
```

Pass condition:

- Python present
- Harbor root has `README.md`
- Terminal-Bench root has `README.md`

Docker may be missing at early design/inspection stages, but any real runner/verifier stage must be blocked until Docker is available.

### 2. Dataset Or Task Inspection

Whole dataset:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" inspect --workspace <workspace>
```

Single task:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" inspect --workspace <workspace> --task easy/bfs-shortest-path
```

Pass condition:

- task count is real
- required files are inspected from disk
- reward/test/trajectory fields are read only from existing files

### 3. Topic And Brief Design

AI may draft a brief, but must save it as a real file:

```text
<workspace>/briefs/<task-name>.md
```

Then record evidence:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" record \
  --workspace <workspace> \
  --stage TOPIC_DESIGN \
  --status PASS \
  --evidence <workspace>/briefs/<task-name>.md \
  --note "brief saved"
```

This stage is AI-assisted, not fully automatic.

### 4. TB 2.0 Scaffold

Create or repair the standard layout. Then validate:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" validate-task \
  --workspace <workspace> \
  --task <relative-task-path>
```

Pass condition:

- all required files exist
- Dockerfile exists under `environment/`
- tests and solution are executable or clearly runnable by shell

### 5. Reference Solution Construction

Codex may implement `solution/solve.sh`. After editing:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" validate-task --workspace <workspace> --task <relative-task-path>
```

If Docker/Harbor is available, run the real oracle path with Harbor/Terminal-Bench and store logs under:

```text
<workspace>/evidence/oracle-<task-name>.log
```

If Docker/Harbor is not available, record `BLOCKED_BY_DEPENDENCY`; do not mark oracle PASS.

### 6. Verifier Construction

Codex may implement `tests/test.sh` and `tests/test_outputs.py`. Then validate structure and, if Docker is available, run real verifier.

Required properties:

- tests derive reward from assertions
- no manual reward success without test result
- empty/no-op solution should fail unless the task is intentionally trivial and documented

### 7. Negative Control

Run at least one real negative control when verifier execution is available. Save evidence:

```text
<workspace>/evidence/negative-control-<task-name>.log
```

If negative control passes, mark verifier as `FAIL`.

### 8. Agent Rollout

Only use real Harbor/Terminal-Bench/agent output. Save trajectory and run logs. Never synthesize logs.

If unavailable:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" record \
  --workspace <workspace> \
  --stage AGENT_ROLLOUT \
  --status BLOCKED_BY_DEPENDENCY \
  --note "Harbor/Docker/model runner unavailable"
```

### 9. Delivery Packaging

Single task:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" package \
  --workspace <workspace> \
  --task <relative-task-path> \
  --copy-tasks
```

Batch:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" package --workspace <workspace> --copy-tasks
```

Pass condition:

- `delivery_manifest.json` exists
- `delivery_index.md` exists
- manifest task count matches inspected source
- checksums are generated from real files

### 10. Final Audit

Run:

```bash
python3 "$SKILL_DIR/scripts/tb20_flow.py" audit --workspace <workspace>
```

Pass condition:

- no PASS stage lacks evidence
- packaged manifest exists if delivery was recorded PASS
- blocked stages are explicit

## Reporting Format

When reporting to the user, include only evidence-backed status:

```text
Workspace: <path>
Source root: <path>
Stages:
- DEPENDENCIES: PASS evidence/dependencies.json
- INSPECT: PASS evidence/inspect.json
- ORACLE_RUN: BLOCKED_BY_DOCKER
Delivery:
- manifest: <path or not generated>
- index: <path or not generated>
```

Do not say "passed" unless the stage record or script output says PASS.
