# ALE daemon + 构建部署 Implementation Plan（Plan 3/3）

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** 新增宿主单 daemon（`ale_daemon.sh`）消费后端触发的 `<queue>/<runId>.json`，按 type 分派 stage1/stage2 runner；删除容器内 codex；compose mount 共享目录；补部署文档与 e2e 验证清单。

**Architecture:** daemon 用 Python 校验触发文件（type/run_dir 合法 + type∈{stage1,stage2}），TAB 分隔回传 bash，按 type 调对应 runner（stdout 独占重定向到 `<stage>.log`），runner 漏写终态时补 `failed`，invalid trigger 移入 `.queue-invalid/`。与 Plan 1/2 的文件契约闭环：后端 gateway 写 trigger → daemon 消费 → runner 产 progress/log → gateway 轮询。

**Tech Stack:** Bash + 内联 Python3（daemon）、Docker（Dockerfile/compose）、Markdown（部署/e2e 文档）。

**系列：** ALE 重构第 3 个（最终）计划。依赖 Plan 1（runner `--from-trigger` + `<stage>_progress.json`）与 Plan 2（gateway 写 trigger 契约）。

**设计依据：** `docs/superpowers/specs/2026-06-22-ale-host-unified-design.md`（§6.4 daemon、§6.5 构建、§8 三层失败）。

---

## File Structure

- Create: `scripts/ale_daemon.sh`（单 daemon，取代 `ale_stage2_daemon.sh`）
- Create: `scripts/ale_daemon_test.sh`（集成测试：mock runner + invalid 处理）
- Delete: `scripts/ale_stage2_daemon.sh`
- Modify: `deploy/docker/Dockerfile.spring`（删 codex CLI 安装）
- Modify: `deploy/docker/docker-compose.yml`（server 加 `/data/fly-agent/ale-runs` mount + env）
- Modify: `deploy/docker/.env.example`（加 `ALE_RUNS_HOST_DIR` 等）
- Create: `docs/ale/ALE-deploy-host-daemon.md`（宿主部署步骤）
- Create: `docs/ale/ALE-e2e-checklist.md`（端到端验证清单，用户宿主执行）

**测试命令：**
- daemon 测试：`bash scripts/ale_daemon_test.sh`（从 repo root；用 mock runner + 临时队列，不依赖真 codex/ale）
- daemon 手动：`ALE_QUEUE_DIR=/tmp/q bash scripts/ale_daemon.sh`
- Docker 构建验证（若环境有 docker）：`docker build -f deploy/docker/Dockerfile.spring -t ale-server .`

---

## Task 1: `ale_daemon.sh` + 集成测试

**Files:**
- Create: `scripts/ale_daemon.sh`
- Create: `scripts/ale_daemon_test.sh`

- [ ] **Step 1: 写 daemon** — `scripts/ale_daemon.sh`（逐字）：
```bash
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
```
> `ALE_STAGE1_RUNNER`/`ALE_STAGE2_RUNNER` 环境变量覆盖用于测试注入 mock runner。

- [ ] **Step 2: 写集成测试** — `scripts/ale_daemon_test.sh`（逐字）：
```bash
#!/usr/bin/env bash
# ALE daemon 集成测试：mock runner + 验证分派/日志/progress/invalid 处理。
# 不依赖真 codex/ale。时序敏感（timeout 兜底）。
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp -d)"
QUEUE="$TMP/queue"
mkdir -p "$QUEUE"
trap 'rm -rf "$TMP"' EXIT

# mock runner：打印 + 据 trigger type 写对应 <type>_progress.json (phase=done)
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
```

- [ ] **Step 3: 跑测试** — `bash scripts/ale_daemon_test.sh` → 期望末尾 `ALL DAEMON TESTS PASSED`。若时序 flake，把各 `sleep 4` 调到 5-6。
- [ ] **Step 4: chmod + commit** — `chmod +x scripts/ale_daemon.sh scripts/ale_daemon_test.sh`；`git add`；`feat(ale): add unified host daemon ale_daemon.sh + integration test`

---

## Task 2: 删除旧 daemon

- [ ] **Step 1: 删** — `git rm scripts/ale_stage2_daemon.sh`
- [ ] **Step 2: 确认无引用** — `grep -rn "ale_stage2_daemon" . --include=*.md --include=*.sh --include=*.yml` 应无引用（或更新引用到 ale_daemon）
- [ ] **Step 3: Commit** — `chore(ale): remove obsolete ale_stage2_daemon.sh (superseded by ale_daemon.sh)`

---

## Task 3: Dockerfile.spring 删 codex

**Files:** Modify `deploy/docker/Dockerfile.spring`

- [ ] **Step 1: 读现状** — Read `deploy/docker/Dockerfile.spring`，定位 Node.js + codex 安装段（约 `:33-37`：`curl nodesource setup_22.x | bash` + `apt-get install nodejs` + `npm install -g @openai/codex@latest`）。
- [ ] **Step 2: 删 codex 行** — 删除 `&& npm install -g @openai/codex@latest`（保留 Node.js 安装，因 SWE 等模块可能依赖；见 spec §7 决策 3）。
- [ ] **Step 3: 注释说明** — 在 Node.js 安装段上加注释：`# Node.js 保留（SWE 等模块依赖）；codex CLI 已移出容器，由宿主 daemon 调用（见 docs/ale/ALE-deploy-host-daemon.md）`。
- [ ] **Step 4: 构建验证（若有 docker）** — `docker build -f deploy/docker/Dockerfile.spring -t ale-server-test .`（无 docker 则跳过，在 report 注明）。
- [ ] **Step 5: Commit** — `chore(ale): drop codex CLI from Dockerfile (moved to host)`

---

## Task 4: docker-compose mount + env

**Files:** Modify `deploy/docker/docker-compose.yml` + `deploy/docker/.env.example`

- [ ] **Step 1: compose server volumes 加 mount** — 在 `fly-agent-server` 服务的 `volumes:` 加：
```yaml
      - ${ALE_RUNS_HOST_DIR:-/data/fly-agent/ale-runs}:/data/fly-agent/ale-runs
```
- [ ] **Step 2: compose server environment 加** — 在 `fly-agent-server` 的 `environment:` 加：
```yaml
      ALE_OUTPUT_ROOT: ${ALE_OUTPUT_ROOT:-/data/fly-agent/ale-runs}
      ALE_QUEUE_DIR: ${ALE_QUEUE_DIR:-/data/fly-agent/ale-runs/.queue}
      ALE_FRAMEWORK_ROOT: ${ALE_FRAMEWORK_ROOT:-/home/ubuntu/agents-last-exam}
```
- [ ] **Step 3: .env.example 加** — 在 `.env.example` 加：
```env
# ALE host-execution shared volume + paths.
ALE_RUNS_HOST_DIR=/data/fly-agent/ale-runs
ALE_OUTPUT_ROOT=/data/fly-agent/ale-runs
ALE_QUEUE_DIR=/data/fly-agent/ale-runs/.queue
ALE_FRAMEWORK_ROOT=/home/ubuntu/agents-last-exam
```
- [ ] **Step 4: 语法验证** — `docker compose -f deploy/docker/docker-compose.yml config > /dev/null`（若有 docker compose；否则跳过注明）。
- [ ] **Step 5: Commit** — `chore(ale): mount ale-runs volume + env in compose`

---

## Task 5: 部署文档

**Files:** Create `docs/ale/ALE-deploy-host-daemon.md`

- [ ] **Step 1: 写文档**（逐字骨架）：
```markdown
# ALE 宿主执行部署指南

ALE 采用「后端容器编排 + 宿主 daemon 执行」：后端容器写触发文件到共享卷，宿主 daemon 消费并拉起 codex/ale_run（避免 Docker-in-Docker）。

## 前置
- 宿主已 `git clone` 本仓库并 `git pull` 到最新
- JDK 17、Python 3、Node.js 22（codex CLI 依赖）、`uv` 已装
- ALE 框架 `agents-last-exam` 已 clone 到 `$ALE_FRAMEWORK_ROOT`（默认 `/home/ubuntu/agents-last-exam`）并 `uv sync`

## 一次性安装

### 1. 安装 codex skills 到宿主 ~/.codex/skills
\`\`\`bash
bash scripts/install-codex-skills.sh
\`\`\`
（装 `ale-task-factory` 等 skill；之后重启 codex 会话生效）

### 2. 安装 codex CLI（若宿主未装）
\`\`\`bash
npm install -g @openai/codex@latest
codex --version
\`\`\`

### 3. 配置 codex 模型（~/.codex/config.toml）
按需配置 `model` / provider。

## 启动 daemon（持久化）

建议用 systemd 托管。示例 unit `/etc/systemd/system/ale-daemon.service`：
\`\`\`ini
[Unit]
Description=ALE host daemon
After=network.target

[Service]
Type=simple
WorkingDirectory=/home/ubuntu/fly_agent
Environment=ALE_QUEUE_DIR=/data/fly-agent/ale-runs/.queue
Environment=ALE_FRAMEWORK_ROOT=/home/ubuntu/agents-last-exam
ExecStart=/bin/bash /home/ubuntu/fly_agent/scripts/ale_daemon.sh
Restart=always
User=ubuntu

[Install]
WantedBy=multi-user.target
\`\`\`
\`\`\`bash
sudo systemctl daemon-reload && sudo systemctl enable --now ale-daemon
sudo journalctl -u ale-daemon -f
\`\`\`

或临时跑：`nohup bash scripts/ale_daemon.sh > /data/fly-agent/ale-daemon.log 2>&1 &`

## 启动后端容器
\`\`\`bash
cd deploy/docker && docker compose up -d fly-agent-server
\`\`\`
确认 server 容器与宿主共享 `/data/fly-agent/ale-runs`（compose volume）。

## 排障
- run 一直 RUNNING：daemon 是否在跑（`systemctl status ale-daemon`）？查 `.queue-invalid/` 有无毒丸触发文件。
- stage1 codex 报 skill 找不到：`bash scripts/install-codex-skills.sh` 重装；重启 codex。
- stage1 Oracle failed "venv not ready"：`cd $ALE_FRAMEWORK_ROOT && uv sync`。
- 日志：`<run_dir>/stage1.log`、`stage2.log`、`stage{1,2}_progress.json`。
```
- [ ] **Step 2: Commit** — `docs(ale): host-execution deployment guide`

---

## Task 6: e2e 验证清单（用户宿主执行）

**Files:** Create `docs/ale/ALE-e2e-checklist.md`

- [ ] **Step 1: 写清单**（逐字骨架，用户在宿主勾选）：
```markdown
# ALE 端到端验证清单

在宿主 + 后端容器部署完成后，按序勾选。本清单需真实环境（GCP/ALE 框架/claude code 凭证），无法在 CI 自动化。

## 环境就绪
- [ ] 宿主 daemon 运行：`systemctl status ale-daemon`（active）
- [ ] 后端容器运行：`docker compose ps`（fly-agent-server Up）
- [ ] 共享卷一致：容器内 `ls /data/fly-agent/ale-runs/.queue` == 宿主 `ls`
- [ ] codex skill 已装：`ls ~/.codex/skills/ale-task-factory/SKILL.md`
- [ ] ALE venv 就绪：`cd $ALE_FRAMEWORK_ROOT && uv run python -c "import cua_bench"`
- [ ] claude code 可用：`claude --version` 且凭证已配

## Stage1（任务工厂）
- [ ] 前端「启动生成」→ run 进入 RUNNING
- [ ] 进度条推进（不卡 20%）：`<run_dir>/stage1_progress.json` 的 percent 变化
- [ ] 日志可见：stage1 面板实时滚动 codex 输出
- [ ] 完成：run COMPLETED，`tasks/*/main.py` + `oracle-evidence.json` 生成
- [ ] task_id 与 DB ale_task.task_id exact 一致（无模糊匹配）

## Stage2（测评执行）
- [ ] 前端「开始测评」→ stage2_status RUNNING，自动切 stage2 面板
- [ ] 日志可见：stage2 面板实时滚动 ale_run 输出（不再只有进度条）
- [ ] 每任务完成：`results/<task>/result.json` + `stage2_progress.json` percent 推进
- [ ] 完成：stage2_status COMPLETED，`stage2_summary.json` 含 avg_score

## 失败路径（严格契约）
- [ ] stage2 无 verified 任务 → run FAILED（phase=failed），不跑任何任务（验证 fallback 删除）
- [ ] stage1 task ID 不匹配 → run FAILED（exact 契约）
- [ ] daemon 缺失/崩溃 → run 超时 FAILED（gateway 兜底，不永久 RUNNING）
- [ ] stage2 re-trigger → 新一轮不读旧 stage2_progress（dispatch 重置 starting）

## 日志/进度文件
- [ ] `<run_dir>/stage1_progress.json` 与 `stage2_progress.json` 独立（互不污染）
- [ ] `<run_dir>/stage1.log` / `stage2.log` 各自由 daemon 重定向（无交错）
```
- [ ] **Step 2: Commit** — `docs(ale): end-to-end verification checklist`

---

## Self-Review

**Spec 覆盖：**
- §6.4 daemon（Python 校验 + TAB + type 分派 + 独占重定向 + invalid 移 .queue-invalid + 终态兜底）→ Task 1 ✓
- §6.4 删 ale_stage2_daemon.sh → Task 2 ✓
- §6.5 Dockerfile 删 codex（保留 Node.js）→ Task 3 ✓
- §6.5 compose mount + env → Task 4 ✓
- §6.5 部署文档（install skills + 起 daemon + uv sync）→ Task 5 ✓
- §10 e2e（含负向：无 verified/ID 不匹配/daemon 缺失/re-trigger）→ Task 6 ✓

**占位符：** 无 TBD；daemon/test/文档均含完整内容。

**一致性：**
- daemon 调 runner 用 `--from-trigger <trigger> --run-dir <run_dir>`（与 Plan 1 runner 的 `load_trigger` 契约一致）✓
- daemon 日志重定向 `<run_dir>/<stage>.log`（与 Plan 2 gateway.tailLog 一致）✓
- daemon 兜底写 `<stage>_progress.json`（与 Plan 1 runner 写的文件一致）✓
- compose mount `/data/fly-agent/ale-runs`（与 Plan 2 AleProperties 默认 outputRoot 一致）✓

**e2e 局限：** Task 6 是清单（用户宿主执行），非自动化测试——已显式声明。

---

## Execution Handoff

Plan complete. 延续 Subagent-Driven。daemon 测试 `bash scripts/ale_daemon_test.sh`；Dockerfile/compose 验证依赖 docker（无则跳过注明）；e2e 由用户在宿主勾选 Task 6 清单。
