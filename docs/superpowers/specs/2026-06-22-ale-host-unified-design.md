# ALE 统一宿主执行模型 — 设计文档

- 日期：2026-06-22
- 分支：feature-ale
- 状态：草案（待评审）
- 关联代码：`fly-agent-service/.../ale/*`、`tools/ale-task-factory/scripts/ale_stage{1,2}_runner.py`、`scripts/ale_stage2_daemon.sh`、`deploy/docker/Dockerfile.spring`、`deploy/docker/docker-compose.yml`

---

## 1. 背景与问题诊断

### 1.1 现状

ALE（Agents' Last Exam）评测流程在 fly-agent 平台上分为两阶段：

- **Stage1（任务工厂）**：后端 `AleStage1Service` 在**容器内**直接 `codex exec` 调用 `ale-task-factory` skill 生成任务包，并解析 Oracle 结果。
- **Stage2（测评执行）**：后端 `AleStage2Service` 写触发文件，由**宿主** `ale_stage2_daemon.sh` 拉起 `ale_stage2_runner.py`，调用宿主上的 ALE 框架 + claude code 跑已验证任务。

### 1.2 三个已确认问题及根因

#### 问题 1：codex 在容器内加载不到 skill

- `AleStage1Service.buildCommand`（`AleStage1Service.java:471-485`）构造 `codex exec --cd {后端cwd} ... "Use the ALE task factory skill ..."`，仅以 skill **名字**引用，依赖 codex 从 `~/.codex/skills/` 发现 skill。
- `Dockerfile.spring` 只 `COPY tools/swe-pro-production`（`:42`），**未 COPY `codex-skills/`，也未 `RUN install-codex-skills.sh`**。
- compose 设 `HOME: /tmp/fly-agent-home`（`docker-compose.yml:95`），容器内 `CODEX_HOME=/tmp/fly-agent-home/.codex` 既无 `config.toml` 也无 skills。
- 根因：**镜像内根本没有 skill 文件**，容器内 codex 必然加载失败；本地能跑是因为宿主 `~/.codex/skills` 装过。
- 旁证：commit `04ed8b7 add agents/openai.yaml for codex skills + install script` 后又 `0993ed2 remove unnecessary openai.yaml`，说明 skill 注册机制反复折腾、脆弱。
- 连带：`ale.framework-root` 默认 `/Users/liuyifei/Liu/github/agents-last-exam`（`AleProperties.java:11`），容器内不存在，stage1 的 Oracle Level1（`ale --dry-run`）/ Level2（`TaskLoader`）无法在容器内执行。

#### 问题 2：stage2 看不到过程输出，只有进度条

- 前端 `getAleStage2Log` → `AleStage2Controller.log`（`:42-46`）→ `AleStage2Service.tailLog`（`:87-103`）读取 `run.getLogPath()`。
- `run.log_path` 是 **stage1 建 run 时**设的 `runDir/codex.log`（`AleStage1Service.java:135`）；**V13 schema 未新增 stage2 日志字段**。
- stage2 真实输出在两处，均未被读取：daemon 重定向的 `.stage2-queue/<run_id>.log`（`ale_stage2_daemon.sh:23`，仅 runner 的 print）；每任务 `results/<task>/agent-log/`（`ale_stage2_runner.py:250-263`）。
- `ale_stage2_runner.run_one_task` 用 `capture_output=True`（`ale_stage2_runner.py:196-203`），`ale_run` 实时过程被吞，仅失败时截前 2000 字符进 `result.error`。
- 根因：**日志路径链路错位**，stage2 日志从未与后端读取的字段对接。

#### 问题 3：codex 在容器内、ale/claude code 在容器外

- codex：`Dockerfile.spring:33-37` 装在 spring 容器内。
- ALE 框架：`ale_stage2_daemon.sh:10` `FRAMEWORK_ROOT=/home/ubuntu/agents-last-exam`，daemon 在宿主运行。
- claude code（被测 agent）：`exp.yaml` 引用 `configs/agents/claude_code.yaml`（`ale_stage2_runner.py:167`），由宿主 `ale_run` 拉起。
- 隐患：frameworkRoot 三处默认值不一致（SKILL.md `/Users/liuyifei/...`、application 同、daemon/runner `/home/ubuntu/...`）；stage1 产物（容器 outputRoot）与 stage2 消费（宿主 daemon）文件系统割裂；compose 未 mount `/data/fly-agent/ale-runs`（`docker-compose.yml:129-133` 仅挂 swe-output/logs/swe-agent/docker.sock）。
- 根因：**“半内半外”拓扑使 skill 文件、ALE 框架、run 产物、日志在两个不互通的文件系统视图之间断裂**，问题 1、2 均为其症状。

### 1.3 关键发现：stage1 runner 已存在但被后端绕过

`tools/ale-task-factory/scripts/ale_stage1_runner.py` 已是功能完整的 stage1 执行器：

- `main()`（`:585`）接受 `--domain --task-id --framework-root --output-root` 等参数；
- `run_codex()`（`:108`）内部调用 `codex exec` 并把输出合并写入 `codex.log`；
- `validate_generated_tasks()`（`:346`）执行三层 Oracle 校验（ALE dry-run / TaskLoader / oracle-evidence.json）；
- `write_summary()`（`:514`）写出含 `oracle_validation.by_task` 的 `summary.json`。

但 `AleStage1Service.executeRun` **完全未调用该 runner**，而是在后端重新实现了 `buildCommand`/`runCodex`（且更简陋——后端不做 Oracle 校验，仅解析 runner 本该产出的 `summary.json`）。这是一处典型重复实现，是本设计要消除的核心冗余。

> 注意：该 runner 现为**单任务**语义（单个 `--task-id` + `:648-649` 自拼时间戳 output 目录），统一宿主执行时需按 §6.3 重构为批量 + `--run-dir`/`--from-trigger`，并非无缝替换。

---

## 2. 目标与非目标

### 目标

1. 后端只编排、不执行：不再直接 `codex exec`，不再硬编码队列路径。
2. 单一执行入口：stage1（codex）与 stage2（ale_run）由同一个宿主 daemon 按 `type` 分派。
3. 消除重复：后端统一走两个现成 runner，删除后端重复的 codex 调用链。
4. 统一可观测：日志与进度走同一套文件契约，stage1/stage2 对称。
5. **严格契约、不推测兼容**：所有跨进程边界（后端↔daemon↔runner）一律用精确文件契约传递——exact task IDs、verified-only、`progress.phase` 终态。**禁止任何“猜一个合理值继续跑”的 fallback**：删除模糊匹配、降级跳过、兜底猜测。失败即 `phase=failed`，不产出看似可用的中间态。

### 非目标

- 不改动 ALE 官方框架（`agents-last-exam`）本身。
- 不引入新的中间件（消息队列、DB 轮询替换等）；保持“文件系统队列 + 文件协议”的轻量解耦风格。
- 不改前端交互流程（仅后端日志/进度来源变化，前端契约不变）。

---

## 3. 总体架构

### 3.1 物理拓扑（改后）

```
┌──────── 后端容器 fly-agent-server（编排层）─────────┐
│  AleStage1Service / AleStage2Service               │
│        │ 共用                                       │
│  AleExecutionGateway                                │
│   · 写触发文件到共享队列                             │
│   · 轮询 <stage>_progress.json → 更新 progress_*   │
│   · 提供 tailLog(stage) 读 <stage>.log              │
│   · 等 progress 终态(done/failed) → 解析 summary/result 落库│
└────────┬────────────────────────────────────────────┘
         │ 共享 mount: /data/fly-agent/ale-runs
         ▼  触发文件 <runId>.json
┌──────── 宿主 scripts/ale_daemon.sh（单 daemon）─────┐
│  while true; 每 3s 扫 .queue/*.json:                │
│    type=stage1 → ale_stage1_runner.py --run-dir …   │
│    type=stage2 → ale_stage2_runner.py --run-dir …   │
│  子进程 stdout/stderr 合并 → <run_dir>/<stage>.log   │
│  退出后兜底写 <stage>_progress.json → rm 触发文件    │
└────────┬────────────────────────────────────────────┘
         ├──────────┬──────────────┐
         ▼          ▼              ▼
   codex CLI    ALE 框架       claude code
  (宿主           (/home/ubuntu/  (被测 agent)
  ~/.codex/      agents-last-exam)
  skills 有
  ale-task-factory)
   ↑ 全部位于宿主同一文件系统视图
```

### 3.2 职责划分

| 层 | 职责 | 不再负责 |
|---|---|---|
| 后端容器 | 建 run/task 记录；写 request/plan；写触发文件；轮询 progress/log；解析 summary/result 落库；REST | ~~调 codex~~、~~估进度（文件探测）~~、~~硬编码队列路径~~ |
| 宿主 daemon | 扫队列；按 type 分派 runner；重定向日志；runner 漏写终态时补 failed；invalid→`.queue-invalid/`；rm 触发文件 | ~~业务校验~~、~~伪造 run progress~~ |
| stage1 runner | 批量 codex 生成（exact task IDs）+ 三层 Oracle 校验（不可降级）+ 写 summary + 写 progress（日志走 stdout） | ~~单 task-id~~、~~自拼时间戳 output 子目录~~、~~skip-oracle 降级~~ |
| stage2 runner | 仅消费 summary 的 verified 任务 → 逐任务 ale_run + 收集 result + 写 stage2_summary + 写 progress（日志走 stdout） | ~~静默 capture_output~~、~~内部写 stage2.log~~、~~verified 猜测 fallback~~ |

---

## 4. 统一契约

### 4.1 共享存储与路径

统一约定到宿主 `/data/fly-agent/ale-runs`，并 mount 进 server 容器同路径：

- 运行根（outputRoot）：`${ALE_OUTPUT_ROOT:-/data/fly-agent/ale-runs}`
- 队列目录：`${ALE_QUEUE_DIR:-/data/fly-agent/ale-runs/.queue}`
- 单个 run 目录：`<outputRoot>/<runKey>`（runKey 由后端生成，保持现有 `domain__scenario__uuid8` 规则）

compose 增 mount（server 服务）：
```yaml
volumes:
  - ${ALE_RUNS_HOST_DIR:-/data/fly-agent/ale-runs}:/data/fly-agent/ale-runs
```

### 4.2 触发文件

路径：`<queue>/<runId>.json`，daemon 消费后 `rm`。

```json
{
  "type": "stage1",
  "run_id": 123,
  "run_dir": "/data/fly-agent/ale-runs/computing_math__task-authoring__a1b2c3d4",
  "stage1": {
    "framework_root": "/home/ubuntu/agents-last-exam",
    "codex_model": "gpt-5.5",
    "tasks": [
      { "task_id": "computing_math/task_authoring_01", "title": "task-authoring #1" }
    ],
    "request": { "domain": "...", "scenario": "...", "difficulty": "..." }
  }
}
```

stage2 时将 `stage1` 段替换为：
```json
{
  "type": "stage2",
  "run_id": 123,
  "run_dir": "...",
  "stage2": {
    "framework_root": "/home/ubuntu/agents-last-exam",
    "agent": "claude_code",
    "model": "claude-sonnet-4-6",
    "timeout": 7200
  }
}
```

> 说明：把 `request` 与 **`tasks` 契约**内联进触发文件。`tasks` 是后端 `createTasks` 已建好的 `ale_task` 行（`task_id` 已确定，格式 `{domain}/{scenario.replace('-','_')}_{%02d 序号}`，对齐 `AleStage1Service.java:248-249`（`scenario.replace('-', '_') + "_" + String.format("%02d", i)`）），作为 **exact 契约**下发给 runner——runner 必须要求 codex 生成**且仅生成**这些 `task_id`，`summary.json` 也必须按这些 ID 返回；少生成 / 多生成 / ID 不匹配一律判 failed。这样后端无需再做模糊匹配。`target_count` 不再单独传，以 `tasks.length` 为准。后端仍可写一份 `request.json` 到 run_dir 便于审计。

### 4.3 按阶段隔离的 progress 文件

**每个阶段独立一个文件**，互不覆盖、互不污染（严格契约，不靠时序保证）：

- `<run_dir>/stage1_progress.json` — stage1 runner 写、gateway 在 stage1 轮询时读
- `<run_dir>/stage2_progress.json` — stage2 runner 写、gateway 在 stage2 轮询时读

gateway 与 daemon 兜底都**按当前阶段**读写对应文件；stage2 只写 `stage2_progress.json`，**绝不读取或继承** `stage1_progress.json`。“stage2 不继承 stage1 进度”由文件隔离天然保证，不依赖“runner 足够快覆盖”。文件内容格式：

```json
{
  "stage": "stage1",
  "phase": "codex_running",
  "current_task": null,
  "percent": 35,
  "counts": { "total": 1, "completed": 0, "failed": 0, "blocked": 0 },
  "ts": "2026-06-22T10:00:00Z",
  "message": "codex generating task package"
}
```

phase 枚举（两阶段统一词表）：
- 通用：`starting` / `done` / `failed`
- stage1：`codex_running` / `oracle_validating`
- stage2：`prepare` / `task_running` / `summarizing`

### 4.4 日志文件

- `<run_dir>/stage1.log`：stage1 runner 的 codex 输出 + Oracle 校验输出。
- `<run_dir>/stage2.log`：stage2 runner 的过程输出 + 每个 `ale_run` 任务的过程。
- **单一写入者**：runner 自身**不**直接打开 `<stage>.log`，全部过程打到自身 stdout/stderr；由 daemon 用 `> <run_dir>/<stage>.log 2>&1` **独占重定向**。这样避免 runner 与 daemon 两个 fd 同时写同一文件造成的交错/截断。
- stage2 每任务的 agent 轨迹仍落 `<run_dir>/results/<domain>__<task>/agent-log/`（origin_log / output / shell.log），不并入主日志。

---

## 5. 数据流

### 5.1 stage1

```
用户「启动生成」
 → AleStage1Service.startRun：
     建 ale_run(CREATED) + ale_task 行（保留现有逻辑）
 → executeRun（重构后）：
     写 request.json 到 run_dir（审计用）
     AleExecutionGateway.dispatch(type=stage1, run_dir, stage1={...})
       → 写 <queue>/<runId>.json
       → 轮询循环(3s)：读 stage1_progress.json.percent → updateProgress(ale_run)
                        ;（前端）tail stage1.log
       → 终止以 progress.phase∈{done,failed} 为准（触发文件删除仅表已消费）
     parseOracleResults(run_dir/summary.json)（保留）
     applyOracleResults / updateTaskCounts（保留）
     ale_run.status ← COMPLETED / BLOCKED / FAILED
```

### 5.2 stage2

```
用户「开始测评」
 → AleStage2Service.startStage2：
     校验 stage1=COMPLETED 且 stage2≠RUNNING（保留）
     置 stage2_status=RUNNING/progress=0/startedAt（保留）
 → executeStage2（重构后）：
     AleExecutionGateway.dispatch(type=stage2, run_dir, stage2={...})
       → 写触发文件 → 轮询 progress → tail stage2.log → 等 progress 终态(done/failed)
     parseAndApplyResults(run_dir/stage2_summary.json + 各 result.json)（保留）
     ale_run.stage2_status ← COMPLETED / FAILED
```

---

## 6. 详细改动

### 6.1 后端

**新增 `AleExecutionGateway`**（`fly-agent-service/.../ale/AleExecutionGateway.java`）：

```java
// 伪代码示意，非最终实现
class AleExecutionGateway {
    // 写触发文件并阻塞轮询至 daemon 完成；期间通过回调更新进度
    StageResult dispatchAndWait(Long runId, Path runDir, TriggerPayload payload,
                                ProgressCallback onProgress);
    // 按 stage 读对应日志末尾 N 行
    List<String> tailLog(Path runDir, String stage, int lines);
}
```

- **dispatch 前重置当前阶段进度**：进入 `dispatchAndWait` 先原子覆盖 `<run_dir>/<stage>_progress.json` 为 `{stage, phase:"starting", percent:0}`，避免 stage2 重跑时读到上一次的 `done` 终态而误判完成（stage2 允许在 FAILED/COMPLETED 后 re-trigger）。可选增强：trigger/progress 带 `attempt_id`、gateway 只认当前 attempt 的终态——本期先用覆盖重置（最小改动）。
- **触发文件原子写**：先 `Files.writeString(<runId>.json.tmp, payload)`，再 `Files.move(tmp, <runId>.json, ATOMIC_MOVE, REPLACE_EXISTING)`。daemon 的 glob `*.json` 不匹配 `.json.tmp`，只在 move 完成的瞬间看到完整文件，杜绝读到半截 JSON。
- **轮询与终态判定**：每 3s 读**当前阶段**的 `stage{1,2}_progress.json`（见 §4.3）；`percent` 变化才写库。gateway **只认两种终态 + 超时**，不做“触发文件是否删除”的推测：
  1. `phase==done` → 正常结束，落库后返回；
  2. `phase==failed` → 失败结束（读 `message`）；
  3. 否则继续轮询，直到超过超时阈值 → FAILED（见下）。

  触发文件是否被 daemon 删除，gateway **不关心**（那是 daemon 内部状态）。daemon/runner 任何异常导致无终态，统一由「超时」兜底——这是后端唯一的兜底层，不叠加重复判断。
- **超时兜底**：从 `dispatchAndWait` 开始计时，elapsed 超过 `stage1-timeout-minutes`（stage1，默认 90）/ `stage2-timeout-minutes`（stage2，默认 240）且当前阶段 `<stage>_progress.json` 仍无 `done/failed` → 标 FAILED 并读最后 progress/log。**与触发文件是否删除无关**。
- **日志读取**：按 stage 解析 `Paths.get(output_root).resolve(stage + ".log")`（路径由 `ale_run.output_root` 推导，无需 DB 字段）。

**`AleStage1Service` 改造**：

- 删除：`buildCommand`、`runCodex`、`estimateProgress`、`logSize`、`hasGeneratedTasks`、`hasOracleEvidence`、`codexModelOptions` 中解析 `~/.codex/config.toml` 的逻辑（`codexConfigPath` / `addToml*` 系列）。
- `executeRun` 改为：写 request.json → 把 `createTasks` 产出的 `[{task_id,title}]` 作为 `trigger.tasks` 传入 `gateway.dispatchAndWait(...)`（exact 契约）→ 解析 Oracle 结果落库。
- `findOracleResult`（`:333-346`）**删除 suffix 模糊匹配**（`:340-344` 的 `endsWith` 双向匹配），只保留 exact `taskId.equals`；runner summary 必须按 `trigger.tasks` 的 `task_id` 返回，匹配不上的 task 即 FAILED（不再“猜它是同一个”）。
- `tailLog` 改为委托 `gateway.tailLog(runDir, "stage1", lines)`（路径由 `output_root` 推导，不读 DB 字段）。
- `startRun` 中 `run.setLogPath(...)` 不再使用（旧 `log_path` 字段保留为历史数据，不写入新值）；日志路径统一由 `output_root/stage{1,2}.log` 推导。
- 保留：`createTasks`、`parseOracleResults`、`applyOracleResults`、`updateTaskCounts`、`writeSummaryIfMissing`、DTO 映射。
- `getOptions` 的模型下拉改为读配置项 `ale.codex-models`（见 6.6）。

**`AleStage2Service` 改造**：

- 删除：`findRunnerScript`（死代码）、`estimateStage2Progress`、硬编码 `STAGE2_QUEUE_DIR`。
- `executeStage2` 改为 `gateway.dispatchAndWait(type=stage2, ...)` → `parseAndApplyResults`（保留）。
- `tailLog` 委托 `gateway.tailLog(runDir, "stage2", lines)`。
- 保留：状态前置校验、`parseAndApplyResults`、DTO 映射。

**Controller / DTO**：

- `AleStage1Controller` / `AleStage2Controller`：不变（端点与契约不变）。
- `AleRunDTO`：日志路径由 `output_root/stage{1,2}.log` 推导或后端 tail 端点按 stage 返回，无需新增 DTO 字段。

### 6.2 数据库

**默认不改 schema**：日志路径由 `ale_run.output_root` 推导（`<output_root>/stage1.log` / `stage2.log`），无需新增列；旧 `log_path` 字段保留为历史数据，不再写入新值。本次**不新增 migration**。

> 可选（审计增强）：若希望 DB 直接可查日志路径，可后续加 `V14` 增 `stage1_log_path` / `stage2_log_path` 两列；非必须，不影响功能。

### 6.3 runner 改造

**`ale_stage1_runner.py`**（实质重构，非无缝替换）：

当前 runner 是**单任务**语义：`:589` 要求单个 `--task-id`，`:648-649` 自拼 `<domain>__<task-id>__<时间戳>` 子目录为 output。本次需做以下改造：

- **改批量语义 + exact task 契约**：移除 `--task-id`（required→废弃），改为从触发文件读 `stage1.tasks`（后端建好的 `[{task_id,title}]` 列表）；一次 `codex exec` 批量生成（与 SKILL “produce the batch” 语义一致）。runner **必须要求 codex 生成且仅生成这些 `task_id`**，`summary.json` 按这些 ID 返回；少生成 / 多生成 / ID 不匹配 → `phase=failed`（不猜测、不补齐）。
- **删除 Oracle 降级路径**：移除 `--skip-oracle-validation`（`:604`）及 venv 不可用时的自动跳过（`:616-632`）。ALE venv 不可用（`uv run python -c "import cua_bench"` 失败）→ 直接 `phase=failed`，`message` 写清 `uv sync` / 依赖缺失。**不产出未验证任务**（`oracleMustPassBeforeStage2` 是硬约束，daemon 触发路径不暴露 skip）。
- **接受 `--run-dir`**：以给定 run_dir 为 output_root，**删除 `:648-649` 自拼时间戳子目录逻辑**（与 stage2 runner 对齐）。
- **接受 `--from-trigger <queue>/<runId>.json`**：从中读取 `framework_root / codex_model / tasks / request`。
- **日志只走 stdout**：`run_codex` 不再 `open(codex.log)`，改为 `subprocess.run(stdout=sys.stdout, stderr=sys.STDOUT)`；Oracle 校验用 `print`。daemon `> stage1.log 2>&1` 独占收集（见 §4.4）。runner 内部**不**写 `stage1.log`。
- **写 `stage1_progress.json`**（`try/finally` 保证终态；仅 stage1 阶段文件，见 §4.3）：
  - 起始 `{phase:"starting",percent:5}`；
  - codex 运行期间起 watcher 线程每 5s 探测 run_dir 产物（复用现有产物判定规则迁入 runner）刷新 percent、`phase:"codex_running"`；
  - Oracle 阶段 `phase:"oracle_validating"`，按完成度推进；
  - 正常结束 `phase:"done"`；任何异常 `finally` 写 `phase:"failed"` + message。
- 默认 `--framework-root` 改为 `/home/ubuntu/agents-last-exam`。
- 保留三层 Oracle 校验、`write_summary`（对 N 个任务产出 `oracle_validation.by_task` 列表）。

**`ale_stage2_runner.py`**：

- **日志只走 stdout**：`run_one_task` 移除 `capture_output=True`，改为 `stdout=sys.stdout, stderr=sys.STDOUT`（`ale_run` 过程实时透传）；daemon `> stage2.log 2>&1` 独占收集。失败时 runner 用自身维护的内存缓冲（或读取刚写入的 `result.json`）填 `error` 字段（仍截前 2000 字符），不再依赖被吞的 `proc.stderr`。
- **接受 `--from-trigger`**：从触发文件读 `stage2.{framework_root, agent, model, timeout}`（与 stage1 对齐，消除命令行参数重复）。
- **删除 verified 猜测 fallback**：`get_verified_tasks`（`:69-125`）**只接受** `summary.json → oracle_validation.by_task[status=verified]`，删除扫描 `oracle-evidence.json` 与 `:108-123` 的 main.py/task_card.json 猜测；无 summary / 无 verified / 结构异常 → `phase=failed`，不跑任何任务（符合 SKILL「blocked = non-runnable」）。
- **写 `stage2_progress.json`**（`try/finally` 保证终态；仅 stage2 阶段文件，见 §4.3；**不读取 `stage1_progress.json`**）：每完成一个任务更新 `percent = completed/total*100`、`counts`、`current_task`；起始 `phase:"prepare"`、逐任务 `phase:"task_running"`、汇总 `phase:"summarizing"`、正常结束 `phase:"done"`、异常 `phase:"failed"`。
- 保留 `prepare_tasks` / `collect_task_result` / `write_summary` 逻辑。

### 6.4 daemon

新增 `scripts/ale_daemon.sh`（取代 `ale_stage2_daemon.sh`）：

```bash
#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STAGE1_RUNNER="$REPO_ROOT/tools/ale-task-factory/scripts/ale_stage1_runner.py"
STAGE2_RUNNER="$REPO_ROOT/tools/ale-task-factory/scripts/ale_stage2_runner.py"
QUEUE_DIR="${ALE_QUEUE_DIR:-/data/fly-agent/ale-runs/.queue}"
FRAMEWORK_ROOT="${ALE_FRAMEWORK_ROOT:-/home/ubuntu/agents-last-exam}"

INVALID_DIR="$QUEUE_DIR/.queue-invalid"
mkdir -p "$QUEUE_DIR" "$INVALID_DIR"
echo "[daemon] watching $QUEUE_DIR"

while true; do
  for trigger in "$QUEUE_DIR"/*.json; do
    [ -f "$trigger" ] || continue
    run_id=$(basename "$trigger" .json)

    # Python 负责完整校验（JSON 合法 + type/run_dir 齐全 + type∈{stage1,stage2}），
    # 以 TAB 分隔回传 "type<TAB>run_dir"；任何不合法 → 非零退出。bash 不做空格拆字符串。
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

    # daemon 唯一兜底：runner 退出后若当前阶段 progress 无终态（runner 被 kill），补 failed
    python3 - "$run_dir" "$type" <<'PY' 2>/dev/null || true
import json, os, sys
run_dir, stage = sys.argv[1], sys.argv[2]
p = f"{run_dir}/{stage}_progress.json"
try:
    d = json.load(open(p)) if os.path.exists(p) else {}
except Exception:
    d = {}   # 文件损坏也视为无终态，照样补 failed
if d.get("phase") not in ("done", "failed"):
    json.dump({"stage": stage, "phase": "failed", "percent": 100,
               "message": "runner exited without final progress"}, open(p, "w"))
PY
    rm -f "$trigger"
  done
  sleep 3
done
```

- 单队列、按 `type` 分派。**触发文件由 Python 完整校验**（JSON 合法、`type`/`run_dir` 齐全且 `type∈{stage1,stage2}`），以 TAB 分隔回传 bash；bash 不做空格拆字符串（路径含空格也安全）。
- **终态权威性（daemon 唯一兜底层）**：runner 自身 `try/finally` 写 `done`/`failed` 为权威终态；daemon 仅在 runner 进程退出后、**当前阶段的 `<stage>_progress.json`** 仍非 `done/failed`（runner 被 kill）才补一笔 `failed`；随后 `rm` 触发文件（`rm` 仅表示“已消费”，gateway 不以此判终态）。
- **invalid trigger**：JSON 损坏 / 缺 `type` 或 `run_dir` / 未知 `type` → 移入 `<queue>/.queue-invalid/` + 写 daemon 日志，**不伪造 run progress**（该 run 由 gateway 超时兜底 FAILED）。原脚本在 `set -e` 下解析失败会拖死整个 daemon，本次一并修复为容错。
- 删除旧 `scripts/ale_stage2_daemon.sh`。

### 6.5 构建与部署

**`Dockerfile.spring`**：

- 删除 codex CLI 安装行：移除 `npm install -g @openai/codex@latest`（`:37`）。
- 保留 Node.js 22.x 安装（`:34-36`），因 SWE 等模块可能依赖；仅删 codex（见 §7 决策 3）。
- 不再需要 `COPY codex-skills`（skill 在宿主 `~/.codex/skills`，由部署步骤安装）。

**`docker-compose.yml`**：

- server 服务 `volumes` 增加 `/data/fly-agent/ale-runs:/data/fly-agent/ale-runs`。
- `.env.example` 增加 `ALE_RUNS_HOST_DIR=/data/fly-agent/ale-runs`。

**部署步骤（写入 `docs/ale/ALE-deploy-host-daemon.md`，新增）**：

1. 宿主 `git pull`；
2. 宿主执行 `bash scripts/install-codex-skills.sh`（装 `ale-task-factory` 等 skill 到 `~/.codex/skills`）；
3. 启动 daemon：`nohup bash scripts/ale_daemon.sh > /data/fly-agent/ale-daemon.log 2>&1 &`（建议用 systemd/PM2 托管，本文档给出 systemd unit 示例）；
4. 确认宿主 `agents-last-exam` 框架已 `uv sync`，`claude` CLI 可用且配好凭证。

### 6.6 配置

`application.yml` / `application-dev.yml` 的 `ale:` 段：

```yaml
ale:
  codex-binary: ${ALE_CODEX_BINARY:codex}      # 保留（仅信息用，后端不再调用）
  output-root: ${ALE_OUTPUT_ROOT:/data/fly-agent/ale-runs}   # 改为绝对路径
  framework-root: ${ALE_FRAMEWORK_ROOT:/home/ubuntu/agents-last-exam}
  queue-dir: ${ALE_QUEUE_DIR:/data/fly-agent/ale-runs/.queue}  # 新增
  codex-models: ${ALE_CODEX_MODELS:gpt-5.5,gpt-5-mini,gpt-5-codex}  # 新增，逗号分隔
  stage1-timeout-minutes: ${ALE_STAGE1_TIMEOUT_MIN:90}    # 新增，stage1 dispatch 后 elapsed 上限
  stage2-timeout-minutes: ${ALE_STAGE2_TIMEOUT_MIN:240}   # 新增，stage2 dispatch 后 elapsed 上限（多任务可调大）
```

`AleProperties.java` 增对应字段：`queueDir`、`codexModels`（List）、`stage1TimeoutMinutes`、`stage2TimeoutMinutes`。`outputRoot` 默认值改为绝对路径。

`getOptions` 的 `codexModelOptions()` 改为读 `properties.getCodexModels()`，删除 `~/.codex/config.toml` 解析。

---

## 7. 关键决策记录

| # | 决策点 | 选定 | 理由 | 可调 |
|---|---|---|---|---|
| 1 | stage1 进度粒度 | **runner watcher 线程探测产物** | codex 是黑盒阻塞进程；watcher 每 5s 探测产物（brief/draft/scaffold/main.py/oracle-evidence）刷新 percent，比“停住旋转”体验好；逻辑从后端迁入 runner，复用现有规则 | 可降级为粗粒度（仅 phase 推进） |
| 2 | codex 模型列表来源 | **application.yml 静态配置** | codex 挪宿主后容器读不到宿主 `~/.codex/config.toml`；配置化最简单、可环境覆盖 | 可改为 daemon 暴露“可用模型”文件 |
| 3 | Dockerfile Node.js | **保留 Node.js，仅删 codex** | 避免误伤 SWE 等可能依赖 Node 的模块；codex 是本次唯一确定要移出容器的 | 若确认无模块依赖 Node，可一并删 |

> 以上 3 点采用推荐默认；若评审需调整，仅影响局部实现，不影响整体骨架。

---

## 8. 错误处理与防卡死

**三层各只承担一层失败处理，不叠加、不互相掩盖：**

1. **runner（唯一业务终态写入者）**：任何业务失败（codex 失败、Oracle 不通过、venv 缺失、task ID 不匹配、无 verified 任务）→ `try/finally` 写 `progress.phase=failed` + 具体 `message`，退出非 0。不产出“看似可用”的中间态。
2. **daemon（唯一进程边界兜底）**：仅当 runner 进程退出后 `progress.phase` 仍非 `done/failed`（runner 被 kill）才补一笔 `failed`。invalid trigger（JSON 损坏 / 缺字段 / 未知 type）→ 移入 `.queue-invalid/` + 日志，**不伪造 run progress**。
3. **gateway（唯一后端兜底）**：只认当前阶段 `<stage>_progress.json` 的 `phase ∈ {done, failed}`；两者皆无则继续轮询，dispatch 后 elapsed 超过 `stage1-timeout-minutes`（默认 90）/ `stage2-timeout-minutes`（默认 240）→ FAILED，读最后 progress/log 写 errorMessage。阈值可经环境变量/配置覆盖。

> 不再有“触发文件删除即结束”“删除但无终态猜测 FAILED”“progress 缺失影响判定”等多层重叠判断。当前阶段 `<stage>_progress.json` 读不到时仅**进度显示**沿用上一帧 percent，**不影响终态判定**（终态只由上述三层决定）。

- **并发**：后端 stage1 线程池保留 `newFixedThreadPool(2)`；daemon 串行处理（`concurrency:1` 与现有 stage2 一致），避免宿主资源争抢。

---

## 9. 清理范围（删除清单）

遵循 CLAUDE.md「禁止冗余设计、禁止无限套娃兼容历史逻辑」，**不保留** `execution-mode=local` 双路径。一次性删除：

- `AleStage1Service`：`buildCommand`、`runCodex`、`estimateProgress`、`logSize`、`hasGeneratedTasks`、`hasOracleEvidence`、`codexConfigPath`、`addToml*` 系列；`findOracleResult` 的 suffix 模糊匹配（`:340-344`）→ 改 exact-only。
- `AleStage2Service`：`findRunnerScript`、`estimateStage2Progress`、常量 `STAGE2_QUEUE_DIR`。
- `ale_stage1_runner.py`：单 `--task-id` 语义、`:648-649` 自拼时间戳 output 目录、`--skip-oracle-validation`（`:604`）及 venv 缺失自动降级（`:616-632`）。
- `ale_stage2_runner.py`：`get_verified_tasks` 的全部 fallback（扫描 `oracle-evidence.json`、`:108-123` main.py/task_card.json 猜测）→ 改 verified-only。
- `scripts/ale_stage2_daemon.sh`（被 `ale_daemon.sh` 取代）。
- `Dockerfile.spring`：`npm install -g @openai/codex@latest`。

保留只读兼容：旧 `ale_run.log_path` 字段（历史数据读取）。

---

## 10. 测试策略

- **runner 单元（python）**：
  - stage1：mock `codex`/`ale_run` 子进程，验证 stdout 内容（codex 输出 + Oracle 校验日志）、`stage1_progress.json` 终态推进、`summary.json` 的 `oracle_validation.by_task`；验证三层 Oracle 校验的 verified/blocked/failed 分支。**不验证 `stage1.log`**——runner 不直接写该文件（日志单一写入者，见 §4.4）。
  - stage2：mock `ale_run`，验证逐任务写 `stage2_progress.json`、stdout 含每个 `ale_run` 的命令与过程输出、`result.json`/`stage2_summary.json` 正确。**不验证 `stage2.log`**（同上）。
- **daemon（bash）**：构造不同 `type` 的触发文件，验证分派、**runner stdout/stderr 被重定向成 `stage1.log`/`stage2.log`**、终态兜底（含 `<stage>_progress.json` 损坏时补 `failed`）、`rm`。
- **后端 gateway（java）**：用临时目录模拟文件系统（触发文件/`<stage>_progress.json`/日志），验证轮询状态机、超时兜底、tailLog 按 stage 读取。
- **端到端**：本地起 `ale_daemon.sh`，跑一个 hello-world 任务，前端验证 stage1 与 stage2 均能看到实时日志与进度推进。
- **严格契约负向测试（必须覆盖，对应 §2 第 5 条与 §8 三层规则）**：
  - stage1：task ID 少生成 / 多生成 / 与 `trigger.tasks` 不匹配 → `phase=failed`；ALE venv 缺失 → `phase=failed`（不降级、不产出未验证任务）。
  - stage2：无 `summary.json` / 无 verified 任务 / `oracle_validation` 结构异常 → `phase=failed` 且不跑任何任务（验证 fallback 已彻底删除）；stage2 只写 `stage2_progress.json`、**从不读取** `stage1_progress.json`——文件隔离天然杜绝跨阶段进度污染（见 §4.3）。
  - daemon：损坏 JSON / 缺 `type` 或 `run_dir` / 未知 `type` → 移入 `.queue-invalid/`，**不伪造 run progress**；模拟 runner 被 kill（无终态退出）→ daemon 补 `failed`。
  - gateway：仅有中间态 progress + 触发超时 → FAILED；`done`/`failed` 各自正确落库；触发文件已删除但 `progress` 无终态 → **不提前判定**，继续轮询直至超时（验证 gateway 不做“删除即结束”的推测）。

---

## 11. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 宿主 daemon 未启动 → 所有 run 永远 RUNNING | 后端超时兜底标 FAILED + 部署文档强提示；可选：后端启动时探测 queue 目录可写并告警 |
| 宿主 skill 未安装 → codex 报错 | 部署文档步骤化；runner 启动时探测 `~/.codex/skills/ale-task-factory/SKILL.md`，缺失则写 progress failed 给出指引 |
| 宿主 ALE venv 未就绪 → Oracle 无法校验 | runner 探测 `uv run python -c "import cua_bench"` 失败即 `phase=failed`，`message` 指明 `uv sync`/依赖缺失，**不产出 verified summary**（§6.3，不降级） |
| 共享 mount 路径不一致 → 触发文件不被消费 | 统一为 `/data/fly-agent/ale-runs` 绝对路径；compose 显式 mount |

回滚：改动集中在 `feature-ale` 分支，回滚即 revert 相关 commit；本次默认不改 DB schema（无 migration），向后兼容。

---

## 12. 验收标准

1. **skill 加载**：容器内不再安装 codex；宿主 `~/.codex/skills/ale-task-factory` 存在时，stage1 能成功驱动 codex 生成任务包（stage1.log 可见 codex 正常输出）。
2. **stage2 可观测**：stage2 运行期间前端日志面板实时滚动 `stage2.log`（含每个 `ale_run` 任务的命令与输出），进度条按任务完成度推进。
3. **进度准确**：`stage{1,2}_progress.json` 由对应阶段 runner 写出，后端 `ale_run.progress_percent` 与当前阶段文件一致；不再出现“长期卡在 25%”。
4. **无重复实现**：后端不再含 `buildCommand`/`runCodex`；codex 唯一调用点在 `ale_stage1_runner.py`。
5. **防卡死**：runner 异常退出或 daemon 缺失时，run 最终进入 FAILED（有 errorMessage），不永久占用线程。
6. **单 daemon**：`scripts/ale_daemon.sh` 同时服务 stage1/stage2；`ale_stage2_daemon.sh` 已删除。

---

## 附录 A：状态机

```
ale_run.status (stage1)：
  CREATED → RUNNING → COMPLETED
                   ↘ BLOCKED   (全部任务 Oracle 阻塞)
                   ↘ FAILED    (codex 失败 / 超时 / runner 异常)

ale_run.stage2_status：
  (null) → RUNNING → COMPLETED
                  ↘ FAILED
```

## 附录 B：run 目录结构（改后）

```
<data/fly-agent/ale-runs>/<runKey>/
├── request.json          # 后端写，审计
├── stage1.log            # stage1 过程日志（codex + Oracle）
├── stage2.log            # stage2 过程日志（runner + ale_run）
├── stage1_progress.json  # stage1 进度（独立文件）
├── stage2_progress.json  # stage2 进度（独立文件，不继承 stage1）
├── summary.json          # stage1 Oracle 结果
├── stage2_summary.json   # stage2 聚合结果
├── codex.log             # (legacy 历史产物；重构后 runner 不再写，新流程下可无)
├── tasks/<domain>/<task>/...
└── results/<domain>__<task>/
    ├── result.json
    └── agent-log/...
```
