---
name: ale-task-factory
description: Produce ALE-native task packages for stage 1. Use this skill to generate batches of Agents' Last Exam tasks with task_card.json, ALE-loadable main.py, deterministic graders, hidden references, and oracle evidence before model evaluation.
---

# ALE Task Factory

## Goal

Produce ALE-native task packages that are ready for stage-2 model execution only after oracle validation passes. The output is a batch directory containing generated task packages, evidence, and a machine-readable summary. Use the local ALE framework root from `ALE_FRAMEWORK_ROOT` or the run plan's `frameworkRoot`; default to `/Users/liuyifei/Liu/github/agents-last-exam`.

## Hard Rules

1. Generate packages under `<output-root>/tasks/<domain>/<task_name>/`, matching ALE task ids `<domain>/<task_name>`.
2. Every task must include `task_card.json` and `main.py` that follow the local ALE examples: `@cb.tasks_config`, `@cb.setup_task`, and `@cb.evaluate_task`.
3. Put deterministic scoring code in `scripts/score_<task>.py` and focused tests in `scripts/test_score_<task>.py` when the evaluator is non-trivial.
4. `evaluate()` must return `[0.0]` for missing or invalid output. It should raise only for infrastructure failures such as unreadable hidden references.
5. `start()` must be idempotent and must not place reference answers in the agent-visible workspace.
6. Keep hidden reference material outside agent-visible input/output paths. Stage-1 oracle validation may read it only to prove the grader can score a known-good output.
7. Do not run real model evaluation in stage 1.
8. Finish by writing a concise `summary.json` with counts, generated task ids, domains, statuses, task dirs, and evidence paths.

## Required Output Layout

```text
<output-root>/<run-id>/
├── request.json
├── plan.json
├── summary.json
├── codex.log
└── tasks/
    └── <domain>/<task_id>/
        ├── task_card.json
        ├── main.py
        ├── scripts/
        │   ├── score_<task>.py
        │   └── test_score_<task>.py
        ├── input/
        ├── reference/
        ├── oracle/
        │   └── expected_output/
        └── oracle-logs/
            └── oracle-evidence.json
```

## ALE Interface Checks

- `task_card.json` must contain at least `taskId`, `title`, `summary`, `category`, and `vm` with `snapshot`, `vcpus`, `memory_gb`, `disk_gb`, and `timeout_s`.
- `main.py` must be importable with the local ALE repository on `PYTHONPATH` and must expose a load function decorated with `@cb.tasks_config(split="train")`.
- `load()` should return `cb.Task` objects whose metadata includes visible input paths, output paths, and hidden reference paths.
- `start()` prepares output and validates staged visible input. It should not synthesize the hidden answer in the workspace.
- `evaluate()` scores only submitted output against hidden reference data and returns a one-item score list.

## Quality Bar

- Task prompt is self-contained: visible input paths, required output paths, file format, allowed tools, forbidden shortcuts, and scoring boundaries.
- Reference answer is not visible to the agent.
- Oracle evidence proves every generated task passed non-LLM validation with score `1.0` using a known-good output before model evaluation.
- The batch favors diversity across domain, discipline, workflow type, data shape, and required artifact type.
- Duplicate detection should use normalized title, domain, task intent, input/output schema, and grader target behavior.

## Failure Handling

If a task cannot be made oracle-verified within the current run, mark that task `blocked` in `summary.json` with a specific reason. Do not silently include blocked tasks in runnable output.
