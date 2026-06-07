---
name: tb20-dataset-production
description: Use this skill to batch produce Terminal-Bench 2.0 source tasks from existing instruction.md inputs through a rigorous AI-agent production workflow. It creates no placeholder tasks and provides only filesystem/state gates for intent analysis, design review, implementation, oracle positive control, negative controls, source audit, and source packaging. It does not run Harbor agents or collect agent-logs; use tb20-batch-execution-delivery for execution delivery.
---

# TB2.0 Dataset Production

This skill is for producing TB2.0 source datasets from `instruction.md` inputs. `instruction.md` is the task-intent input, not a magic one-line prompt. A real AI coding agent must perform the production work, write the task files, and leave evidence for each gate.

## Boundary

Use this skill for:

- batch ingesting existing `instruction.md` files
- preparing per-task production packets for an AI coding agent
- producing or repairing source task files
- enforcing AI-agent review evidence and execution-proof gates before handoff
- packaging a clean source dataset without `agent-logs/`

Do not use this skill for:

- one-shot prompt generation of tasks
- hardcoded or enumerated fake task generation
- placeholder scaffold creation
- Harbor rollout, model execution, or delivery log collection

Use `tb20-batch-execution-delivery` after this skill passes `SOURCE_AUDIT`.

## Source Contract

The source dataset must match the client demo contract:

```text
README.md
README_zh.md
easy/<task-name>/
medium/<task-name>/
hard/<task-name>/
```

Each task must contain:

```text
task.toml
instruction.md
environment/Dockerfile
solution/solve.sh
tests/test.sh
tests/test_outputs.py
```

`agent-logs/` is forbidden during dataset production. Execution delivery creates it from real Harbor runs.

## Production Route

1. `init`: create a production workspace and target dataset root.
2. `ingest-instruction`: copy each input `instruction.md` into the target task path and create a production packet.
3. AI agent production: run Codex, Claude Code, or another capable coding agent against the production packet. The agent must write the source task files and the required evidence files.
4. `source-audit`: enforce source layout, metadata consistency, no placeholders, verifier shape, solution shape, and all production evidence.
5. `package`: copy the clean source dataset only after `SOURCE_AUDIT` passes.
6. Handoff the packaged source dataset to `tb20-batch-execution-delivery`.

There is intentionally no `scaffold` command. A placeholder task is worse than no task because it can enter batch execution and create false confidence.

## Required Evidence

For every task, the production agent must write these files under the workspace evidence directory shown in the production packet:

```text
01-intent-analysis.md
02-test-design.md
03-test-review.md
04-implementation-review.md
05-oracle-positive.md
06-negative-controls.md
07-final-review.md
```

Evidence expectations:

- `01-intent-analysis.md`: objective, constraints, underspecified points, minimal assumptions, and whether the instruction blocks truthful production.
- `02-test-design.md`: natural-language derivation from instruction to verifier: intended behavior, observable outputs, edge cases, ambiguity handling, anti-cheat strategy, and why each test is necessary.
- `03-test-review.md`: independent critique of the test design: missing behavior, overfitting, false positives, false negatives, hardcoding risks, and required revisions.
- `04-implementation-review.md`: environment, fixtures, solution approach, verifier approach, and dependency choices.
- `05-oracle-positive.md`: exact reference-solution/verifier commands and observed pass output.
- `06-negative-controls.md`: plausible wrong solution or failure mode and observed verifier rejection.
- `07-final-review.md`: source contract, intent alignment, reviewer concerns resolved, oracle pass, negative control failure, no placeholders, and execution handoff readiness.

If intent is insufficient, mark the task BLOCKED in `01-intent-analysis.md`; do not invent facts.

## Test Design Process

The skill does not claim a hardcoded schema can create rigorous tests. The production agent must do the reasoning:

1. Extract the true user intent and constraints from `instruction.md`.
2. Identify ambiguity and either make a minimal defensible assumption or block production.
3. Design tests before implementation, including normal behavior, edge cases, and plausible wrong approaches.
4. Run an independent AI-agent review of the test design and revise until the review has no unresolved material objections.
5. Implement verifier and reference solution.
6. Prove the reference solution passes.
7. Prove at least one plausible wrong solution fails.

`source-audit` cannot judge semantic excellence by itself. It only blocks tasks that lack the required agent reasoning trail, real command evidence, or clean source contract.

## Commands

Bootstrap once:

```bash
SKILL_DIR=/path/to/tb20-dataset-production
"$SKILL_DIR/scripts/bootstrap_runtime.sh"
```

Create a production workspace:

```bash
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_dataset.py" init \
  --workspace <production-workspace> \
  --dataset-root <dataset-root>
```

Batch ingest instructions:

```bash
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_dataset.py" ingest-instruction \
  --workspace <production-workspace> \
  --difficulty easy \
  --instruction <path/to/instruction.md>
```

The command writes a production packet under:

```text
<production-workspace>/production-packets/
```

Give each packet to a real coding agent. The script does not pretend to solve the production task.

Audit before handoff:

```bash
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_dataset.py" source-audit \
  --workspace <production-workspace>
```

Package only after audit passes:

```bash
"$SKILL_DIR/.venv/bin/python" "$SKILL_DIR/scripts/tb20_dataset.py" package \
  --workspace <production-workspace> \
  --output <source-delivery-root>
```

## Difficulty Policy

Use the demo-aligned policy:

- `easy`: expert 15-30 minutes, junior around 75 minutes; direct tool/algorithm, crisp IO.
- `medium`: expert 35-45 minutes, junior 150-210 minutes; multi-step realistic engineering workflow.
- `hard`: expert 480-600 minutes, junior 2400-3000 minutes; paper/standard-level implementation, binary/protocol/adversarial analysis, or deep systems work.

`task.toml` metadata must keep `difficulty`, `expert_time_estimate_min`, and `junior_time_estimate_min` consistent with the task directory and design evidence.

## Reporting

Report only evidence-backed status:

```text
Workspace: <production-workspace>
Dataset root: <dataset-root>
Tasks: <count>
Source audit: PASS/FAIL
Evidence: <production-workspace>/evidence/
Next: tb20-batch-execution-delivery
```
