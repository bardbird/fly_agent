#!/usr/bin/env bash
# ALE 单 daemon：轮询队列，按 type 分派 stage1/stage2 runner。
# Run: nohup bash scripts/ale_daemon.sh > /data/fly-agent/ale-daemon.log 2>&1 &
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STAGE1_RUNNER="${ALE_STAGE1_RUNNER:-$REPO_ROOT/tools/ale-task-factory/scripts/ale_stage1_runner.py}"
STAGE2_RUNNER="${ALE_STAGE2_RUNNER:-$REPO_ROOT/tools/ale-task-factory/scripts/ale_stage2_runner.py}"
QUEUE_DIR="${ALE_QUEUE_DIR:-/data/fly-agent/ale-runs/.queue}"
INVALID_DIR="$QUEUE_DIR/.queue-invalid"

mkdir -p "$QUEUE_DIR" "$INVALID_DIR"
echo "[daemon] watching $QUEUE_DIR"

while true; do
  for trigger in "$QUEUE_DIR"/*.json; do
    [ -f "$trigger" ] || continue
    run_id=$(basename "$trigger" .json)

    # Python 完整校验（JSON 合法 + type/run_dir 齐全 + type∈{stage1,stage2}），TAB 分隔回传。
    # bash 不做空格拆字符串（路径含空格也安全）。损坏/缺字段/未知 type → 非零退出。
    line=$(python3 - "$trigger" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1]))
    t, r = d.get("type"), d.get("run_dir")
    if t not in ("stage1", "stage2") or not r:
        sys.exit(2)
    print(f"{t}\t{r}")
except Exception:
    sys.exit(1)
PY
) || {
      mv "$trigger" "$INVALID_DIR/${run_id}.json" 2>/dev/null || rm -f "$trigger"
      echo "[daemon] invalid trigger $run_id → .queue-invalid/ (run will time out on backend)"
      continue
    }
    type=${line%%$'\t'*}
    run_dir=${line#*$'\t'}

    case "$type" in
      stage1) python3 "$STAGE1_RUNNER" --from-trigger "$trigger" --run-dir "$run_dir" > "$run_dir/stage1.log" 2>&1 || true ;;
      stage2) python3 "$STAGE2_RUNNER" --from-trigger "$trigger" --run-dir "$run_dir" > "$run_dir/stage2.log" 2>&1 || true ;;
    esac

    # daemon 唯一兜底：runner 退出后若当前阶段 progress 无终态（runner 被 kill），补 failed。
    # 文件损坏也视为 {} 照样补（try/except）。
    python3 - "$run_dir" "$type" <<'PY' 2>/dev/null || true
import json, os, sys
run_dir, stage = sys.argv[1], sys.argv[2]
p = f"{run_dir}/{stage}_progress.json"
try:
    d = json.load(open(p)) if os.path.exists(p) else {}
except Exception:
    d = {}
if d.get("phase") not in ("done", "failed"):
    json.dump({"stage": stage, "phase": "failed", "percent": 100,
               "message": "runner exited without final progress"}, open(p, "w"))
PY
    rm -f "$trigger"
  done
  sleep 3
done
