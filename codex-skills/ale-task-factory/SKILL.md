---
name: ale-task-factory
description: Produce ALE-native task packages for stage 1. Use this skill to generate batches of ALE tasks with task_card.json, main.py, pure grader, oracle implementation, and oracle evidence before model evaluation.
---

# ALE Task Factory

## Goal

Produce ALE-native task packages that are ready for stage-2 model execution only after oracle validation passes. The output is a batch directory containing generated task packages, evidence, and a machine-readable summary.

## Hard Rules

1. Generate ALE-native packages under `tasks/<domain>/<task_id>/`.
2. Every task must include `task_card.json`, `main.py`, `scripts/score_<task>.py`, `scripts/test_score_<task>.py`, and `oracle/run.py`.
3. Write the grader before or alongside task scaffolding. The grader must be deterministic and must not import `oracle`.
4. `oracle/run.py` must not import `scripts/score_*`, call an LLM, call web search, or read hidden reference files.
5. `evaluate()` must return `[0.0]` for missing or invalid output. It should raise only for infrastructure failures.
6. `start()` must be idempotent and must keep reference data hidden from the agent-visible workspace.
7. Do not run real model evaluation in stage 1.
8. Finish by writing a concise `summary.json` with counts, generated task ids, domains, statuses, and evidence paths.

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
        ├── oracle/
        │   ├── run.py
        │   └── oracle-meta.json
        └── oracle-logs/
            └── oracle-evidence.json
```

## Quality Bar

- Task prompt is self-contained: visible input paths, required output paths, file format, allowed tools, forbidden shortcuts, and scoring boundaries.
- Reference answer is not visible to the agent.
- Oracle evidence proves every generated task passed non-LLM validation with score `1.0`.
- The batch favors diversity across domain, discipline, workflow type, data shape, and required artifact type.
- Duplicate detection should use normalized title, domain, task intent, input/output schema, and grader target behavior.

## Failure Handling

If a task cannot be made oracle-verified within the current run, mark that task `blocked` in `summary.json` with a specific reason. Do not silently include blocked tasks in runnable output.
