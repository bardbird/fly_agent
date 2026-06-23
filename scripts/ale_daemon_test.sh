#!/usr/bin/env bash
# ALE daemon 集成测试：mock runner + 验证分派/日志/progress/invalid 处理。
# 不依赖真 codex/ale。时序敏感（timeout 兜底）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
QUEUE="$TMP/queue"
mkdir -p "$QUEUE"
trap 'rm -rf "$TMP"' EXIT

cat > "$TMP/mock_runner.py" <<'EOF'
import sys, json, os
trigger = sys.argv[sys.argv.index("--from-trigger") + 1]
run_dir = sys.argv[sys.argv.index("--run-dir") + 1]
t = json.load(open(trigger))["type"]
with open(os.path.join(run_dir, f"{t}_progress.json"), "w") as f:
    json.dump({"stage": t, "phase": "done", "percent": 100}, f)
print(f"mock runner ran type={t}")
EOF

export ALE_QUEUE_DIR="$QUEUE"
export ALE_STAGE1_RUNNER="$TMP/mock_runner.py"
export ALE_STAGE2_RUNNER="$TMP/mock_runner.py"

timeout 12 bash "$REPO_ROOT/scripts/ale_daemon.sh" > "$TMP/daemon.log" 2>&1 &
DAEMON_PID=$!
sleep 1

# case 1: stage1 trigger → mock runner 跑、stage1.log 有输出、progress done、trigger 删
echo '{"type":"stage1","run_id":1,"run_dir":"'"$TMP"'","stage1":{"framework_root":"/fw","tasks":[{"task_id":"d/t01","title":"T"}]}}' > "$QUEUE/1.json"
sleep 4
grep -q "mock runner ran type=stage1" "$TMP/stage1.log"
grep -q '"phase": "done"' "$TMP/stage1_progress.json"
test ! -f "$QUEUE/1.json"

# case 2: invalid trigger（未知 type）→ 移入 .queue-invalid/
echo '{"type":"bogus","run_dir":"'"$TMP"'"}' > "$QUEUE/2.json"
sleep 4
test -f "$QUEUE/.queue-invalid/2.json"

# case 3: 损坏 JSON → 移入 .queue-invalid/
printf '{not json' > "$QUEUE/3.json"
sleep 4
test -f "$QUEUE/.queue-invalid/3.json"

kill "$DAEMON_PID" 2>/dev/null || true
echo "ALL DAEMON TESTS PASSED"
