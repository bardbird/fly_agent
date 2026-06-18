---
name: ale-task-runner
description: Execute ALE stage 2 — run verified task packages through the ALE framework with Claude Code and collect scores, trajectories, and agent logs.
---

# ALE Task Runner (Stage 2)

## Goal

Take stage-1-verified task packages and execute them through the ALE framework with a real agent (Claude Code). Produce per-task results with scores, agent transcripts, and output files.

## Input

A stage-1 output directory under `<ale-runs>/<run-id>/` containing:
- `summary.json` — parsed to find `verified` tasks
- `tasks/<domain>/<task_name>/` — task packages with `task_card.json`, `main.py`, `input/`, `reference/`
- `oracle-logs/oracle-evidence.json` — proof the task passed oracle validation

Only tasks with `status: "verified"` in stage-1 `summary.json` are eligible for stage 2.

## Output Layout

```
<ale-runs>/<run-id>/
├── exp.yaml                           # experiment config (generated)
├── summary.json                       # aggregate stats (generated/updated)
├── tasks/                             # stage-1 task packages (read-only)
├── results/
│   └── <domain>__<task_name>/         # one dir per executed task
│       ├── result.json                # score, status, duration, errors
│       └── agent-log/
│           ├── transcript.jsonl       # agent turn-by-turn trace
│           ├── shell.log              # all shell command output
│           └── output/                # agent-produced files
└── logs/
    └── ale_run.log                    # framework-level log
```

## Hard Rules

1. **Only run verified tasks.** Parse stage-1 `summary.json` → `oracle_validation.by_task[]` → filter `status == "verified"`. Skip `blocked` and `failed` tasks.
2. **One task at a time.** `concurrency: 1`. Serial execution is safer for Docker resource management.
3. **Use `--task` filter per run.** Generate one `exp.yaml` and invoke `ale_run run exp.yaml --task <domain>/<task_name>` for each task. This avoids restarts interfering across tasks.
4. **Agent is Claude Code.** Always use `configs/agents/claude_code.yaml` with the `direct` provider. The `secret/.env` file provides API keys.
5. **Environment is Docker.** Always use `configs/environments/docker.yaml`. Never use GCP in stage 2.
6. **Task data source is `local:task-data`.** Already configured in `docker.yaml`. The framework expects `task-data/` under the ALE root.
7. **Read timeout from task_card.json.** Each task card has `timeout_s`. Pass it as `wall_time_s` in `exp.yaml`. Default 7200s.
8. **Cleanup after each task.** `cleanup_mode: delete` — destroy the Docker container after scoring to free disk.
9. **Accumulate results per task.** After each task finishes, immediately collect its result and write `results/<domain>__<task_name>/result.json`. Do not wait for all tasks.
10. **Do not modify stage-1 task packages.** The `tasks/` directory is read-only input.

## Execution Flow

### Phase 1 ─ Prepare

1. Read `<run-dir>/summary.json`. Collect all `verified` task IDs.
2. For each verified task, ensure the task exists in `<run-dir>/tasks/<domain>/<task_name>/` and has both `task_card.json` and `main.py`.
3. Symlink each verified task into the ALE framework's `tasks/` tree so `TaskLoader` can find them:
   ```bash
   ln -sf <run-dir>/tasks/<domain>/<task_name> <framework-root>/tasks/<domain>/<task_name>
   ```
4. Write `exp.yaml` to `<run-dir>/exp.yaml`:
   ```yaml
   name: ale_stage2_<run_key>
   secret_file: secret/.env
   agents:
     - configs/agents/claude_code.yaml
   environment: configs/environments/docker.yaml
   tasks: selected_tasks/stage2_<run_key>.txt
   output:
     root: <run-dir>/logs/ale
   concurrency: 1
   cleanup_mode: delete
   ```
5. Write the task list file to the ALE framework's `selected_tasks/` directory.

### Phase 2 ─ Execute (per task loop)

For each verified task:

1. **Run ALE framework**:
   ```bash
   cd <framework-root>
   uv run python -m ale_run run <run-dir>/exp.yaml --task <domain>/<task_name>
   ```
   This boots the Docker container, runs Claude Code, evaluates, and cleans up.

2. **On completion**: find the latest run directory under `<run-dir>/logs/ale/ale_stage2_<run_key>/claude_code/<model>/<task_slug>/v0/<timestamp>/`.

3. **Collect artifacts**:
   - Read `run.json` → extract `status`, `score`, `duration_s`, `error`
   - Copy `trajectory.json` → `results/<domain>__<task_name>/agent-log/transcript.jsonl` (format conversion if needed)
   - Copy `origin_log/claude_code/` → `results/<domain>__<task_name>/agent-log/`
   - Copy `output/` → `results/<domain>__<task_name>/agent-log/output/`

4. **Write `result.json`**:
   ```json
   {
     "task_id": "<domain>/<task_name>",
     "status": "completed | failed | timeout",
     "score": 1.0,
     "duration_s": 312.5,
     "error": null
   }
   ```

5. **On failure**: if `ale_run` exits non-zero or the run directory is missing, mark the task `failed` with the captured error. Continue to the next task.

### Phase 3 ─ Summarize

1. Read all `results/*/result.json` files.
2. Compute aggregate stats.
3. Write final `summary.json` to `<run-dir>/summary.json` (overwrite stage-1 summary).

## Failure Handling

- **ale_run crashes**: mark task `failed`, capture stderr, continue to next task.
- **Docker pull/start fails**: mark task `failed` with infrastructure error, continue.
- **Task times out**: ALE framework handles timeout internally, marks status `timeout`. Collect the partial result normally.
- **Grading fails**: `evaluate()` throws → ALE marks eval as failed. Collect what is available.
- **Disk full**: abort the entire run. Do not continue when disk space is below 20 GB.

## Quality Bar

- Every executed task has a `result.json` with a non-null `status`.
- `summary.json` aggregates are consistent with per-task results.
- Agent logs are preserved even for failed/timeout tasks (partial transcripts are better than nothing).
- No stage-1 task files are modified.
- Docker containers are cleaned up after every task (no dangling containers).
