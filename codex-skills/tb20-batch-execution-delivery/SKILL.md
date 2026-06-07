---
name: tb20-batch-execution-delivery
description: Use this skill to batch execute an existing Terminal-Bench 2.0 dataset with Harbor, collect real agent/verifier artifacts, and package the client delivery tree. Defaults to the claude-code Harbor agent but accepts any Harbor-supported agent. It never creates or repairs tasks; use tb20-dataset-production for dataset production.
---

# TB2.0 Batch Execution Delivery

This skill is only for executing an already-produced TB2.0 dataset and collecting delivery results. It must not create tasks, repair verifiers, or design datasets.

## Boundary

Use this skill for:

- batch discovering existing tasks
- running Harbor with a real agent/model
- defaulting to `claude-code` while allowing `--agent <harbor-agent>`
- collecting real `agent-logs/`
- packaging the final client delivery tree

Do not use this skill for:

- task ideation
- source task scaffolding
- verifier construction
- oracle solution authoring

Use `tb20-dataset-production` for those.

## Required Input

The input dataset must already satisfy the source contract:

```text
README.md
README_zh.md
easy/<task-name>/
medium/<task-name>/
hard/<task-name>/
```

Each task must already contain:

```text
task.toml
instruction.md
environment/Dockerfile
solution/solve.sh
tests/test.sh
tests/test_outputs.py
```

## Required Output

The final delivery must contain the same source files plus real execution logs:

```text
agent-logs/run.json
agent-logs/trajectory.json
agent-logs/verifier/ctrf.json
agent-logs/verifier/reward.txt
```

No `agent-logs` file may be fabricated. All four files must be copied from Harbor job output or the stage fails.

## Runtime Script

Use this skill's own virtualenv and Harbor CLI. Bootstrap once:

```bash
SKILL_DIR=/path/to/tb20-batch-execution-delivery
"$SKILL_DIR/scripts/bootstrap_runtime.sh"
```

```bash
SKILL_DIR=/path/to/tb20-batch-execution-delivery
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" init \
  --workspace <workspace> \
  --source-root <dataset-root> \
  --output-root <delivery-root>

"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" deps --workspace <workspace>
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" inspect --workspace <workspace>

"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" run \
  --workspace <workspace> \
  --agent claude-code \
  --model <model>

"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" collect --workspace <workspace>
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" package --workspace <workspace>
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_execute.py" audit --workspace <workspace>
```

## Mandatory Gates

1. `deps` must pass before `run`.
2. `inspect` must pass before `run`.
3. `run` must invoke Harbor. It may record per-task failures, but it must not synthesize success.
4. `collect` must copy all required log files from Harbor output into each task.
5. `package` requires successful collection for all selected delivery tasks.
6. `audit` requires evidence for every PASS stage and rejects missing delivery artifacts.

## Harbor Command Shape

Local dataset tasks:

```bash
"$SKILL_DIR/.venv/bin/harbor" run -p <task-dir> -a <agent> -m <model> --jobs-dir <workspace>/jobs --yes
```

Registry tasks, when explicitly needed:

```bash
"$SKILL_DIR/.venv/bin/harbor" run -d terminal-bench@2.0 -t <task-name> -a <agent> -m <model> --jobs-dir <workspace>/jobs --yes
```

Use local `-p <task-dir>` for client-produced datasets.

## Reporting

Report only evidence-backed status:

```text
Workspace: <path>
Source root: <path>
Output root: <path>
Agent: <agent>
Model: <model>
Tasks: total/pass/fail
Delivery: <output-root>
Evidence: <workspace>/evidence/
```
