#!/usr/bin/env bash
# ALE Stage2 host-side daemon — polls for trigger files and executes ale_stage2_runner.py
# Run via: nohup bash scripts/ale_stage2_daemon.sh &
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNNER="$REPO_ROOT/tools/ale-task-factory/scripts/ale_stage2_runner.py"
QUEUE_DIR="${ALE_STAGE2_QUEUE_DIR:-/data/fly-agent/ale-runs/.stage2-queue}"
FRAMEWORK_ROOT="${ALE_FRAMEWORK_ROOT:-/home/ubuntu/agents-last-exam}"

mkdir -p "$QUEUE_DIR"

echo "[daemon] watching $QUEUE_DIR"

while true; do
    for trigger in "$QUEUE_DIR"/*.json; do
        [ -f "$trigger" ] || continue
        run_id=$(basename "$trigger" .json)
        run_dir=$(python3 -c "import json; print(json.load(open('$trigger'))['run_dir'])")

        echo "[daemon] starting stage2 for $run_id (dir=$run_dir)"
        python3 "$RUNNER" --run-dir "$run_dir" --framework-root "$FRAMEWORK_ROOT" > "$QUEUE_DIR/$run_id.log" 2>&1
        exit_code=$?

        if [ $exit_code -eq 0 ]; then
            echo "[daemon] $run_id completed OK"
        else
            echo "[daemon] $run_id failed (exit=$exit_code)"
        fi
        rm -f "$trigger"
    done
    sleep 3
done
