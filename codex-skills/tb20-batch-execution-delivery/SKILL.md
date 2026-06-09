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
agent-logs/claude-code.txt
agent-logs/verifier/ctrf.json
agent-logs/verifier/reward.txt
```

No `agent-logs` file may synthesize success. `run.json`, `trajectory.json`, `claude-code.txt`, and `reward.txt` must be copied from Harbor trial output. If the Harbor version does not emit `verifier/ctrf.json`, the execution script may write a minimal CTRF wrapper from the real verifier stdout and real `reward.txt`; the wrapper must preserve pass/fail status and must never convert a failed reward into success.

## Runtime Script

This installable skill is for Codex execution and guidance only. System and backend entrypoints live outside the skill directory under `tools/tb20-production/scripts`.

Use the toolkit virtualenv and the pip-installed Terminal-Bench and Harbor CLIs. Bootstrap once:

```bash
TOOLKIT_DIR=/path/to/tools/tb20-production
"$TOOLKIT_DIR/scripts/bootstrap_runtime.sh"
```

The bootstrap installs:

```bash
pip install -U terminal-bench harbor
```

```bash
TOOLKIT_DIR=/path/to/tools/tb20-production
TB20_VENV=/home/ubuntu/tb20-runtime/.venv
"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" init \
  --workspace <workspace> \
  --source-root <dataset-root> \
  --output-root <delivery-root> \
  --docker-registry-mirrors <mirror-list> \
  --apt-mirror <apt-mirror-url> \
  --python-index-url <python-index-url>

"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" deps --workspace <workspace>
"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" inspect --workspace <workspace>

"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" run \
  --workspace <workspace> \
  --agent claude-code \
  --model <model> \
  --concurrency 1 \
  --force-build \
  --no-delete \
  --require-no-trial-exceptions \
  --require-reward 1

"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" collect --workspace <workspace>
"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" package --workspace <workspace>
"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" audit --workspace <workspace>
```

## Mandatory Gates

1. `deps` must pass before `run`.
2. `inspect` must pass before `run`.
3. `run` must invoke Harbor. It may record per-task failures, but it must not synthesize success.
4. Harbor CLI exit code `0` is not enough. Inspect `workspace/evidence/run.json` for per-trial `exceptionType`, reward, model, and log presence.
5. `collect` must copy all required log files from Harbor output into each task.
6. `package` requires successful collection for all selected delivery tasks.
7. `audit` requires evidence for every PASS stage and rejects missing delivery artifacts.

For final all-green delivery, run with:

```text
--require-no-trial-exceptions --require-reward 1
```

Omit those flags only when the user explicitly wants to collect failure evidence.

## Harbor Command Shape

Local dataset tasks:

```bash
"$TB20_VENV/bin/harbor" run -p <task-dir> -a <agent> -m <model> --jobs-dir <workspace>/jobs --yes
```

Registry tasks, when explicitly needed:

```bash
"$TB20_VENV/bin/harbor" run -d terminal-bench@2.0 -t <task-name> -a <agent> -m <model> --jobs-dir <workspace>/jobs --yes
```

Use local `-p <task-dir>` for client-produced datasets.

Use `--force-build` when the task has a local `environment/Dockerfile` and the `docker_image` tag is not guaranteed to exist locally or in a registry. Use `--no-delete` while debugging or while you need to inspect retained containers/job directories. Do not rely on a shell loop like `"$name:$tag"` in zsh unless the variable is braced (`"${name}:${tag}"`), because zsh can treat `:$tag` as a parameter modifier.

## Claude Code Model Configuration

Local Claude Code settings are not automatically inherited by Harbor agent containers in every environment. Before assuming a model alias works:

1. Inspect the host Claude settings without printing secrets.
2. Prefer the full configured model name, for example `claude-opus-4-7`, over an informal alias like `opus4.7`.
3. Inject the same provider env into the Harbor subprocess when needed:

```bash
"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_execute.py" run \
  --workspace <workspace> \
  --agent claude-code \
  --model claude-opus-4-7 \
  --claude-settings-from-host ~/.claude/settings.json \
  --force-build \
  --no-delete \
  --require-no-trial-exceptions \
  --require-reward 1
```

If the agent log reports an API/model error, fix the provider/model mapping and rerun. Do not package a run where the model was rejected unless the user explicitly asked for failure evidence.

## Current Harbor Log Layout

Harbor versions may write logs under a trial directory like:

```text
<jobs-dir>/<job-name>/<trial-name>/result.json
<jobs-dir>/<job-name>/<trial-name>/agent/trajectory.json
<jobs-dir>/<job-name>/<trial-name>/agent/claude-code.txt
<jobs-dir>/<job-name>/<trial-name>/verifier/reward.txt
<jobs-dir>/<job-name>/<trial-name>/verifier/test-stdout.txt
```

The execution script must collect from this layout and normalize into:

```text
agent-logs/run.json
agent-logs/trajectory.json
agent-logs/claude-code.txt
agent-logs/verifier/reward.txt
agent-logs/verifier/ctrf.json
```

The older flat-file lookup is only a fallback.

## Result Interpretation

Report both infrastructure status and benchmark status:

- Harbor command exit code
- trial exception type, if any
- verifier reward
- model name actually used
- whether `claude-code.txt` and `trajectory.json` were collected

Reward `0` with no exception is a valid model failure, not an infrastructure failure. Reward `0` caused by API/model rejection is an infrastructure/configuration failure and must be rerun after fixing configuration for all-green delivery.

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
