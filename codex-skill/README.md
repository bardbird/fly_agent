# Codex Skills

This directory stores filesystem-driven Codex skills that can be copied or
installed into a Codex runtime. These skills are independent from the Fly Agent
backend, frontend, and database.

## Skills

- `tb20-production-pipeline`: Terminal-Bench 2.0 production workflow with
  strict script-enforced gates, real evidence files, task validation, delivery
  packaging, and no mocked verifier or runner artifacts.

## Install Locally

```bash
mkdir -p ~/.codex/skills
rsync -a codex-skill/tb20-production-pipeline/ ~/.codex/skills/tb20-production-pipeline/
```

## Run The Included Example

```bash
SKILL_DIR=codex-skill/tb20-production-pipeline
WORKSPACE=/tmp/tb20-skill-flow
SOURCE_ROOT="$SKILL_DIR/examples"
OUTPUT_ROOT=/tmp/tb20-skill-delivery

python3 "$SKILL_DIR/scripts/tb20_flow.py" init \
  --workspace "$WORKSPACE" \
  --source-root "$SOURCE_ROOT" \
  --output-root "$OUTPUT_ROOT"

python3 "$SKILL_DIR/scripts/tb20_flow.py" deps --workspace "$WORKSPACE"
python3 "$SKILL_DIR/scripts/tb20_flow.py" inspect --workspace "$WORKSPACE"
python3 "$SKILL_DIR/scripts/tb20_flow.py" validate-task \
  --workspace "$WORKSPACE" \
  --task sqlite-wal-forensic-recovery
python3 "$SKILL_DIR/scripts/tb20_flow.py" package \
  --workspace "$WORKSPACE" \
  --task sqlite-wal-forensic-recovery \
  --copy-tasks
python3 "$SKILL_DIR/scripts/tb20_flow.py" audit --workspace "$WORKSPACE"
```

Docker, Harbor, Terminal-Bench, and model rollouts must be recorded only from
real logs. Missing dependencies should be recorded as `BLOCKED_BY_DEPENDENCY`,
not skipped.
