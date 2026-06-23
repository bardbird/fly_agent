# ALE Runner 契约层改造 Implementation Plan（Plan 1/3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ale_stage1_runner.py` / `ale_stage2_runner.py` 改造为符合「统一宿主执行 + 严格契约」的版本：按阶段隔离的 progress 文件、exact task IDs、verified-only、不降级 Oracle、日志走 stdout，供宿主 daemon 驱动。

**Architecture:** 新增共享 `ale_progress.py`（原子写 `<stage>_progress.json`）；stage2 runner 删除全部 verified fallback、加 `--from-trigger`、写 `stage2_progress.json`、`ale_run` 输出走 stdout；stage1 runner 从单任务语义改为批量（一次 codex 生成 trigger.tasks 指定的 exact IDs）、加 `--run-dir`/`--from-trigger`、删除 Oracle 降级（venv 缺即 failed）、写 `stage1_progress.json`、codex 输出走 stdout。

**Tech Stack:** Python 3（runner 现状为 `from __future__ import annotations`，3.8+）、pytest、`subprocess`、`unittest.mock` / `monkeypatch`。

**系列说明：** ALE 统一宿主执行模型的第 1 个计划。后续 Plan 2（后端编排层）、Plan 3（daemon + 构建部署 + 端到端）依赖本计划产出的契约。本计划产物可独立用 pytest 验证，不依赖后端。

**设计依据：** `docs/superpowers/specs/2026-06-22-ale-host-unified-design.md`（§4.2 触发文件契约、§4.3 按阶段隔离 progress、§6.3 runner 改造）。

---

## File Structure

- Create: `tools/ale-task-factory/scripts/ale_progress.py` — 共享 progress writer（原子写 + 终态语义）
- Create: `tools/ale-task-factory/scripts/ale_progress_test.py`
- Modify: `tools/ale-task-factory/scripts/ale_stage2_runner.py` — verified-only + `--from-trigger` + `stage2_progress.json` + stdout
- Create: `tools/ale-task-factory/scripts/ale_stage2_runner_test.py`
- Modify: `tools/ale-task-factory/scripts/ale_stage1_runner.py` — 批量 + `--run-dir`/`--from-trigger` + exact IDs + 删降级 + `stage1_progress.json` + stdout
- Create: `tools/ale-task-factory/scripts/ale_stage1_runner_test.py`

**职责边界：**
- `ale_progress.py` 只负责「把 progress dict 原子写到给定路径」，不耦合 stage 语义。
- stage1/stage2 runner 只负责各自阶段业务（生成+Oracle / 评测），通过调用 `ale_progress.write_progress` 落进度。
- 日志：runner 全部 `print` / 子进程 stdout 透传，**不**自行打开 `<stage>.log`（由 daemon 重定向）。

---

## 共享约定（所有任务遵循）

**trigger 文件契约**（daemon 透传给 runner 的 `--from-trigger` 文件）：
```json
{
  "type": "stage1",
  "run_id": 123,
  "run_dir": "/data/fly-agent/ale-runs/<runKey>",
  "stage1": {
    "framework_root": "/home/ubuntu/agents-last-exam",
    "codex_model": "gpt-5.5",
    "tasks": [{"task_id": "computing_math/task_authoring_01", "title": "task-authoring #1"}],
    "request": {"domain": "...", "scenario": "...", "difficulty": "..."}
  }
}
```
stage2 段把 `stage1` 替换为 `stage2: {framework_root, agent, model, timeout}`。

**progress schema**（`<run_dir>/<stage>_progress.json`）：
```json
{"stage":"stage1","phase":"codex_running","current_task":null,
 "percent":35,"counts":{"total":1,"completed":0,"failed":0,"blocked":0},
 "ts":"2026-06-23T10:00:00Z","message":"..."}
```
phase 终态仅 `done` / `failed`；中间态：stage1=`starting`/`codex_running`/`oracle_validating`；stage2=`prepare`/`task_running`/`summarizing`。

**task_id 格式**：`{domain}/{scenario.replace('-','_')}_{%02d}`（对齐 `AleStage1Service.java:248-249`）。

**pytest 运行**：`pytest tools/ale-task-factory/scripts/<test_file> -v`（项目 root 下）。

---

## Task 1: 共享 progress writer `ale_progress.py`

**Files:**
- Create: `tools/ale-task-factory/scripts/ale_progress.py`
- Test: `tools/ale-task-factory/scripts/ale_progress_test.py`

- [ ] **Step 1: 写失败测试**

`tools/ale-task-factory/scripts/ale_progress_test.py`：
```python
from __future__ import annotations
import json
from pathlib import Path

from ale_progress import write_progress, is_terminal


def test_write_progress_atomic_and_readable(tmp_path: Path):
    p = tmp_path / "stage1_progress.json"
    write_progress(p, stage="stage1", phase="starting", percent=0, counts={"total": 1})
    assert json.loads(p.read_text(encoding="utf-8")) == {
        "stage": "stage1", "phase": "starting", "percent": 0,
        "counts": {"total": 1}, "current_task": None,
        "message": None,
    }
    # 原子写：不留 .tmp 残留
    assert not (tmp_path / "stage1_progress.json.tmp").exists()


def test_write_progress_overwrites_previous_terminal(tmp_path: Path):
    p = tmp_path / "stage2_progress.json"
    write_progress(p, stage="stage2", phase="done", percent=100,
                   counts={"total": 2, "completed": 2})
    write_progress(p, stage="stage2", phase="starting", percent=0,
                   counts={"total": 2})
    data = json.loads(p.read_text(encoding="utf-8"))
    assert data["phase"] == "starting"  # 新值覆盖旧 done（gateway 重跑重置语义）


def test_is_terminal():
    assert is_terminal("done") is True
    assert is_terminal("failed") is True
    assert is_terminal("codex_running") is False
    assert is_terminal(None) is False
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pytest tools/ale-task-factory/scripts/ale_progress_test.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'ale_progress'`

- [ ] **Step 3: 实现 `ale_progress.py`**

```python
"""Shared progress writer for ALE runners.

Writes <stage>_progress.json atomically (tmp + replace) so the backend gateway
never reads a half-written file. Runner is the sole business writer of the
terminal phase (done/failed); see spec §4.3 / §8.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

TERMINAL_PHASES = ("done", "failed")


def is_terminal(phase: str | None) -> bool:
    return phase in TERMINAL_PHASES


def write_progress(
    path: Path,
    *,
    stage: str,
    phase: str,
    percent: int,
    counts: dict[str, int] | None = None,
    current_task: str | None = None,
    message: str | None = None,
) -> None:
    """Atomically write a progress frame. Overwrites any previous content."""
    payload: dict[str, Any] = {
        "stage": stage,
        "phase": phase,
        "percent": percent,
        "counts": counts or {"total": 0, "completed": 0, "failed": 0, "blocked": 0},
        "current_task": current_task,
        "message": message,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(payload, ensure_ascii=False) + "\n", encoding="utf-8")
    os.replace(tmp, path)  # atomic on POSIX & Windows
```

> 说明：`ts` 不由 writer 注入（writer 无法调用 `datetime.now()`——见下方"时间戳约定"）；如需时间戳，由调用方传入 `message` 或后续扩展字段。本计划不依赖 ts 做判定。

- [ ] **Step 4: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_progress_test.py -v`
Expected: PASS（3 passed）

- [ ] **Step 5: Commit**

```bash
git add tools/ale-task-factory/scripts/ale_progress.py tools/ale-task-factory/scripts/ale_progress_test.py
git commit -m "feat(ale): add shared atomic progress writer"
```

---

## Task 2: stage2 runner —— verified-only + `--from-trigger` + progress + stdout

**Files:**
- Modify: `tools/ale-task-factory/scripts/ale_stage2_runner.py`
- Test: `tools/ale-task-factory/scripts/ale_stage2_runner_test.py`

> 现状回顾（`ale_stage2_runner.py`）：`get_verified_tasks`（`:69-125`）有 3 级 fallback；`run_one_task`（`:182-203`）用 `capture_output=True` 吞掉输出；`main`（`:367`）从命令行参数读 input。本任务改为严格契约。

- [ ] **Step 1: 写失败测试（verified-only）**

`tools/ale-task-factory/scripts/ale_stage2_runner_test.py`：
```python
from __future__ import annotations
import json
from pathlib import Path

import ale_stage2_runner as r


def _write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def test_get_verified_tasks_only_from_summary(tmp_path: Path):
    """只接受 summary.json → oracle_validation.by_task[verified]"""
    _write(tmp_path / "summary.json", {
        "oracle_validation": {"by_task": [
            {"task_id": "d/t01", "status": "verified", "oracle_score": 1.0},
            {"task_id": "d/t02", "status": "blocked"},
        ]}
    })
    verified = r.get_verified_tasks(tmp_path)
    assert [t["task_id"] for t in verified] == ["d/t01"]


def test_get_verified_tasks_no_summary_returns_empty(tmp_path: Path):
    """无 summary → 空（main 依此写 failed），不扫描 oracle-evidence，不猜 main.py"""
    (tmp_path / "tasks" / "d" / "t01").mkdir(parents=True)
    (tmp_path / "tasks" / "d" / "t01" / "main.py").write_text("x", encoding="utf-8")
    (tmp_path / "tasks" / "d" / "t01" / "task_card.json").write_text("{}", encoding="utf-8")
    assert r.get_verified_tasks(tmp_path) == []


def test_get_verified_tasks_malformed_oracle_returns_empty(tmp_path: Path):
    """oracle_validation 结构异常 → 空（不跑任何任务）"""
    _write(tmp_path / "summary.json", {"oracle_validation": {}})
    assert r.get_verified_tasks(tmp_path) == []
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pytest tools/ale-task-factory/scripts/ale_stage2_runner_test.py -v`
Expected: FAIL — `test_get_verified_tasks_no_summary_returns_empty` 仍返回非空（当前 fallback 会把 main.py 目录当 verified）。

- [ ] **Step 3: 改 `get_verified_tasks` 为 verified-only**

把 `ale_stage2_runner.py:69-125` 的 `get_verified_tasks` 整体替换为：
```python
def get_verified_tasks(run_dir: Path) -> list[dict]:
    """只接受 summary.json → oracle_validation.by_task[status=="verified"]。

    无 summary / 无 verified / 结构异常 → 返回 []，由 main 写 phase=failed。
    不扫描 oracle-evidence.json，不据 main.py/task_card.json 猜测（删除全部 fallback）。
    """
    summary_path = run_dir / "summary.json"
    if not summary_path.exists():
        return []
    try:
        summary = _read_json(summary_path)
    except (json.JSONDecodeError, OSError):
        return []
    by_task = summary.get("oracle_validation", {}).get("by_task", [])
    if not isinstance(by_task, list):
        return []
    return [t for t in by_task if isinstance(t, dict) and t.get("status") == "verified"]
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_stage2_runner_test.py -v`
Expected: PASS（3 passed）

- [ ] **Step 5: 写 `--from-trigger` 解析测试**

追加到 `ale_stage2_runner_test.py`：
```python
def test_load_trigger_stage2(tmp_path: Path):
    trigger = tmp_path / "trig.json"
    _write(trigger, {"type": "stage2", "run_id": 7, "run_dir": str(tmp_path),
                     "stage2": {"framework_root": "/fw", "agent": "claude_code",
                                "model": "claude-sonnet-4-6", "timeout": 600}})
    payload = r.load_trigger(trigger)
    assert payload["type"] == "stage2"
    assert payload["stage2"]["framework_root"] == "/fw"
    assert payload["run_dir"] == str(tmp_path)
```

- [ ] **Step 6: 实现 `load_trigger`**

在 `ale_stage2_runner.py` 顶部 helper 区（`_write_json` 之后）加：
```python
def load_trigger(path: Path) -> dict:
    """读取并校验 stage2 触发文件。"""
    data = _read_json(path)
    if data.get("type") != "stage2":
        raise ValueError(f"trigger type != stage2: {data.get('type')}")
    stage2 = data.get("stage2") or {}
    for key in ("framework_root",):
        if not stage2.get(key):
            raise ValueError(f"trigger missing stage2.{key}")
    return data
```

- [ ] **Step 7: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_stage2_runner_test.py -v`
Expected: PASS（4 passed）

- [ ] **Step 8: 写 progress + stdout 测试**

追加到 `ale_stage2_runner_test.py`：
```python
def test_run_one_task_streams_stdout(tmp_path: Path, monkeypatch):
    """run_one_task 不再 capture_output 静默；stdout 实时透传。"""
    import subprocess
    captured = {}

    class FakeProc:
        returncode = 0
        stdout = ""
        stderr = ""

    def fake_run(cmd, **kwargs):
        captured["capture_output"] = kwargs.get("capture_output")
        captured["stdout"] = kwargs.get("stdout")
        captured["stderr"] = kwargs.get("stderr")
        return FakeProc()

    monkeypatch.setattr(subprocess, "run", fake_run)
    r.run_one_task(Path("/fw"), tmp_path / "exp.yaml", "d/t01", timeout_s=60)
    assert captured["capture_output"] is not True   # 不再静默
    assert captured["stdout"] is not None            # 透传到父进程 stdout
```

- [ ] **Step 9: 改 `run_one_task` 为 stdout 透传**

把 `ale_stage2_runner.py:182-203` 的 `run_one_task` 替换为：
```python
def run_one_task(framework_root, exp_yaml, task_id, timeout_s):
    """Invoke ale_run for a single task; output streams to parent stdout/stderr."""
    uv = _find_uv()
    cmd = [uv, "run", "python", "-m", "ale_run", "run", str(exp_yaml), "--task", task_id]
    print(f"  [{task_id}] $ {' '.join(cmd)}")
    return subprocess.run(
        cmd,
        cwd=str(framework_root),
        stdout=sys.stdout,
        stderr=sys.stderr,
        text=True,
        timeout=timeout_s + 300,
        check=False,
    )
```
> 失败时 `result.json` 的 `error` 由 `collect_task_result` 现有逻辑（读 `run.json`/ale_run 退出码）填充；不再依赖被吞的 `proc.stderr`。

- [ ] **Step 10: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_stage2_runner_test.py -v`
Expected: PASS（5 passed）

- [ ] **Step 11: 改 `main` 加 `--from-trigger` + 写 `stage2_progress.json`**

把 `ale_stage2_runner.py:367-463` 的 `main` 替换为（保留 `prepare_tasks` / `collect_task_result` / `write_summary` 不变）：
```python
def main() -> int:
    parser = argparse.ArgumentParser(description="ALE Stage 2 Runner")
    parser.add_argument("--run-dir", required=True, help="Stage-1 output directory")
    parser.add_argument("--from-trigger", required=True, help="trigger json path")
    args = parser.parse_args()

    run_dir = Path(args.run_dir).expanduser().resolve()
    trigger = load_trigger(Path(args.from_trigger).expanduser().resolve())
    s2 = trigger["stage2"]
    framework_root = Path(s2.get("framework_root", ".")).expanduser().resolve()
    agent = s2.get("agent", "claude_code")
    model = s2.get("model", "claude-sonnet-4-6")
    timeout = int(s2.get("timeout", 7200))

    progress = run_dir / "stage2_progress.json"
    def prog(phase, percent, **kw):
        write_progress(progress, stage="stage2", phase=phase, percent=percent, **kw)

    try:
        if not (framework_root / "ale_run").is_dir():
            prog("failed", 100, message=f"ALE framework root invalid: {framework_root}")
            return 2

        prog("prepare", 5)
        verified = get_verified_tasks(run_dir)
        if not verified:
            prog("failed", 100, message="no verified tasks in summary.json")
            return 0  # 非 codex 失败：已如实记录，退出 0 让 daemon 不误判 runner 崩溃

        exp_yaml = prepare_tasks(run_dir, framework_root, verified)
        log_root = run_dir / "logs" / "ale"
        task_results = []
        total = len(verified)
        failed = 0
        for i, t in enumerate(verified):
            task_id = t["task_id"]
            prog("task_running", 10 + int(80 * i / total),
                 counts={"total": total, "completed": i},
                 current_task=task_id)
            print(f"\n[stage2] [{i+1}/{total}] {task_id}")
            domain, task_name = task_id.split("/", 1)
            task_card_path = run_dir / "tasks" / domain / task_name / "task_card.json"
            task_timeout = timeout
            if task_card_path.exists():
                try:
                    task_timeout = _read_json(task_card_path).get("vm", {}).get("timeout_s", timeout)
                except (json.JSONDecodeError, KeyError):
                    pass
            import time
            t0 = time.monotonic()
            proc = run_one_task(framework_root, exp_yaml, task_id, task_timeout)
            elapsed = time.monotonic() - t0
            result = collect_task_result(run_dir, log_root, task_id, proc, elapsed)
            if result["status"] == "failed" and result["error"] is None:
                result["error"] = f"ale_run exit code {proc.returncode}"
            task_results.append(result)
            if result["status"] == "failed":
                failed += 1

        prog("summarizing", 95, counts={"total": total, "completed": total - failed, "failed": failed})
        summary = write_summary(run_dir, task_results, agent, model)
        counts = summary["counts"]
        prog("done", 100, counts=counts,
             message=f"completed={counts['completed']} failed={counts['failed']}")
        return 1 if failed > 0 else 0
    except Exception as exc:  # try/finally 语义：任何异常写终态 failed
        prog("failed", 100, message=f"runner crashed: {type(exc).__name__}: {exc}")
        raise
```
> 注：`from ale_progress import write_progress` 需加到文件 import 区（见 Step 12）。

- [ ] **Step 12: 加 import**

在 `ale_stage2_runner.py` import 区（`from pathlib import Path` 之后）加：
```python
from ale_progress import write_progress
```

- [ ] **Step 13: 跑全部 stage2 测试 + 手动冒烟**

Run: `pytest tools/ale-task-factory/scripts/ale_stage2_runner_test.py tools/ale-task-factory/scripts/ale_progress_test.py -v`
Expected: PASS（全部）

手动冒烟（无 ale_run 框架时的 failed 路径）：
```bash
mkdir -p /tmp/ale-smoke && echo '{"type":"stage2","run_id":1,"run_dir":"/tmp/ale-smoke","stage2":{"framework_root":"/nonexistent"}}' > /tmp/trig.json
python3 tools/ale-task-factory/scripts/ale_stage2_runner.py --run-dir /tmp/ale-smoke --from-trigger /tmp/trig.json
cat /tmp/ale-smoke/stage2_progress.json  # 期望 phase=failed, message 含 invalid
```

- [ ] **Step 14: Commit**

```bash
git add tools/ale-task-factory/scripts/ale_stage2_runner.py tools/ale-task-factory/scripts/ale_stage2_runner_test.py
git commit -m "feat(ale): stage2 runner verified-only + from-trigger + stage2_progress + stdout"
```

---

## Task 3: stage1 runner —— 批量 + `--run-dir`/`--from-trigger` + exact IDs + 删降级 + progress + stdout

**Files:**
- Modify: `tools/ale-task-factory/scripts/ale_stage1_runner.py`
- Test: `tools/ale-task-factory/scripts/ale_stage1_runner_test.py`

> 现状回顾（`ale_stage1_runner.py`）：单任务语义（`:589` `--task-id` required、`:648-649` 自拼时间戳 output 子目录）、`:604` `--skip-oracle-validation`、`:616-632` venv 缺自动降级、`run_codex`（`:108`）写 `codex.log`。本任务改为批量严格契约。

- [ ] **Step 1: 写 `--from-trigger` + exact task 契约测试**

`tools/ale-task-factory/scripts/ale_stage1_runner_test.py`：
```python
from __future__ import annotations
import json
from pathlib import Path

import ale_stage1_runner as r


def _write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def test_load_trigger_stage1(tmp_path: Path):
    trig = tmp_path / "trig.json"
    _write(trig, {"type": "stage1", "run_id": 9, "run_dir": str(tmp_path),
                  "stage1": {"framework_root": "/fw", "codex_model": "gpt-5.5",
                             "tasks": [{"task_id": "d/t01", "title": "T1"}],
                             "request": {"domain": "d", "scenario": "s", "difficulty": "easy"}}})
    payload = r.load_trigger(trig)
    assert payload["type"] == "stage1"
    assert payload["stage1"]["tasks"] == [{"task_id": "d/t01", "title": "T1"}]


def test_load_trigger_rejects_empty_tasks(tmp_path: Path):
    trig = tmp_path / "trig.json"
    _write(trig, {"type": "stage1", "run_id": 9, "run_dir": str(tmp_path),
                  "stage1": {"framework_root": "/fw", "tasks": []}})
    try:
        r.load_trigger(trig)
        assert False, "should reject empty tasks"
    except ValueError:
        pass


def test_check_exact_task_ids_pass(tmp_path: Path):
    expected = ["d/t01", "d/t02"]
    (tmp_path / "tasks" / "d" / "t01").mkdir(parents=True)
    (tmp_path / "tasks" / "d" / "t02").mkdir(parents=True)
    # 现有 _discover_task_dirs 发现 d/t01, d/t02
    r.check_exact_task_ids(tmp_path, expected)  # 不抛


def test_check_exact_task_ids_detects_missing_and_extra(tmp_path: Path):
    (tmp_path / "tasks" / "d" / "t01").mkdir(parents=True)
    (tmp_path / "tasks" / "d" / "t03").mkdir(parents=True)  # 多生成
    try:
        r.check_exact_task_ids(tmp_path, ["d/t01", "d/t02"])  # 缺 t02，多 t03
        assert False, "should raise on mismatch"
    except ValueError as e:
        assert "t02" in str(e) and "t03" in str(e)
```

- [ ] **Step 2: 跑测试确认失败**

Run: `pytest tools/ale-task-factory/scripts/ale_stage1_runner_test.py -v`
Expected: FAIL — `ModuleNotFoundError`（`load_trigger`/`check_exact_task_ids` 尚不存在）。

- [ ] **Step 3: 实现 `load_trigger` + `check_exact_task_ids`**

在 `ale_stage1_runner.py` 的 `write_summary` 之后（`# ── main` 之前）加：
```python
def load_trigger(path: Path) -> dict:
    """读取并校验 stage1 触发文件（exact task 契约）。"""
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("type") != "stage1":
        raise ValueError(f"trigger type != stage1: {data.get('type')}")
    stage1 = data.get("stage1") or {}
    tasks = stage1.get("tasks")
    if not isinstance(tasks, list) or not tasks:
        raise ValueError("trigger stage1.tasks must be a non-empty list")
    for t in tasks:
        if not (isinstance(t, dict) and t.get("task_id")):
            raise ValueError(f"trigger stage1.tasks entry invalid: {t!r}")
    if not stage1.get("framework_root"):
        raise ValueError("trigger missing stage1.framework_root")
    return data


def check_exact_task_ids(output_dir: Path, expected_task_ids: list[str]) -> None:
    """校验 codex 生成且仅生成 expected task_ids；多/少/不匹配 → ValueError。"""
    discovered = {f"{d}/{n}" for d, n, _ in _discover_task_dirs(output_dir)}
    expected = set(expected_task_ids)
    missing = expected - discovered
    extra = discovered - expected
    if missing or extra:
        raise ValueError(
            f"task id mismatch: missing={sorted(missing)} extra={sorted(extra)}"
        )
```

- [ ] **Step 4: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_stage1_runner_test.py -v`
Expected: PASS（4 passed）

- [ ] **Step 5: 写 venv 缺失不降级测试**

追加到 `ale_stage1_runner_test.py`：
```python
def test_venv_check_failure_is_fatal(tmp_path: Path, monkeypatch):
    """venv 不可用 → 不降级 skip，直接返回 failed 信号（main 写 phase=failed）。"""
    import subprocess

    def fake_run(cmd, **kw):
        class P:
            returncode = 1  # cua_bench import 失败
            stdout = ""
            stderr = "ModuleNotFoundError: No module named 'cua_bench'"
        return P()

    monkeypatch.setattr(subprocess, "run", fake_run)
    ok = r.check_ale_venv(Path("/fw"))
    assert ok is False  # 调用方据此写 phase=failed，绝不产出 verified summary
```

- [ ] **Step 6: 实现 `check_ale_venv`（替代旧自动降级逻辑）**

在 `ale_stage1_runner.py`（`_find_uv` 附近）加：
```python
def check_ale_venv(framework_root: Path) -> bool:
    """返回 venv 是否就绪；不在此处副作用，由 main 决定写 failed。"""
    uv = _find_uv()
    result = subprocess.run(
        [uv, "run", "python", "-c", "import cua_bench"],
        cwd=str(framework_root), capture_output=True, text=True,
        timeout=30, check=False,
    )
    return result.returncode == 0
```

- [ ] **Step 7: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_stage1_runner_test.py -v`
Expected: PASS（5 passed）

- [ ] **Step 8: 写 codex stdout 透传 + exact id 校验集成测试**

追加到 `ale_stage1_runner_test.py`：
```python
def test_run_codex_streams_stdout(tmp_path: Path, monkeypatch):
    """run_codex 不再 open(codex.log)；codex 输出透传到父 stdout。"""
    import subprocess
    captured = {}

    class FakeProc:
        returncode = 0

    def fake_run(cmd, **kw):
        captured["stdout"] = kw.get("stdout")
        captured["stderr"] = kw.get("stderr")
        return FakeProc()

    monkeypatch.setattr(subprocess, "run", fake_run)
    r.run_codex(plan_path=tmp_path / "plan.json", output_dir=tmp_path,
                cwd=tmp_path, framework_root=tmp_path)
    import sys
    assert captured["stdout"] is sys.stdout   # 透传，不再 open(codex.log)
```

- [ ] **Step 9: 改 `run_codex` 为 stdout 透传**

把 `ale_stage1_runner.py:108-142` 的 `run_codex` 替换为：
```python
def run_codex(plan_path: Path, output_dir: Path, cwd: Path, framework_root: Path) -> int:
    """codex exec：输出透传到父进程 stdout/stderr（daemon 重定向到 stage1.log）。"""
    output_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "codex", "exec", "--cd", str(cwd),
        "--dangerously-bypass-approvals-and-sandbox",
        (
            f"Use the ALE task factory skill to produce the batch described in {plan_path}. "
            f"Use ALE framework root {framework_root}. "
            f"Write all generated artifacts under {output_dir}. "
            f"Do not run stage-2 model evaluation."
        ),
    ]
    env = os.environ.copy()
    env["ALE_OUTPUT_ROOT"] = str(output_dir.resolve())
    env["ALE_FRAMEWORK_ROOT"] = str(framework_root.resolve())
    env["ALE_STAGE1"] = "true"
    print(f"$ {' '.join(cmd)}")
    proc = subprocess.run(
        cmd, cwd=cwd, env=env,
        stdout=sys.stdout, stderr=sys.stderr,
        text=True, check=False,
    )
    return proc.returncode
```
> 注：`import sys` / `import os` 已在文件顶部；函数返回 int 退出码而非 CompletedProcess（main 只需要退出码）。

- [ ] **Step 10: 跑测试确认通过**

Run: `pytest tools/ale-task-factory/scripts/ale_stage1_runner_test.py -v`
Expected: PASS（6 passed）

- [ ] **Step 11: 重写 `main`：批量 + run-dir + from-trigger + exact IDs + 删降级 + progress**

把 `ale_stage1_runner.py:585-709` 的 `main` 整体替换为（保留 `build_plan` / `validate_generated_tasks` / `write_summary` / `run_codex` 等已改造函数不变）：
```python
def main() -> int:
    parser = argparse.ArgumentParser(description="ALE Stage 1 Runner (batch)")
    parser.add_argument("--run-dir", required=True, help="output root for this run")
    parser.add_argument("--from-trigger", required=True, help="trigger json path")
    args = parser.parse_args()

    run_dir = Path(args.run_dir).expanduser().resolve()
    trigger = load_trigger(Path(args.from_trigger).expanduser().resolve())
    s1 = trigger["stage1"]
    framework_root = Path(s1["framework_root"]).expanduser().resolve()
    codex_model = s1.get("codex_model", "gpt-5.5")
    tasks_contract = s1["tasks"]
    request = s1.get("request", {})
    expected_ids = [t["task_id"] for t in tasks_contract]

    progress = run_dir / "stage1_progress.json"
    def prog(phase, percent, **kw):
        write_progress(progress, stage="stage1", phase=phase, percent=percent, **kw)

    try:
        if not (framework_root / "ale_run").is_dir():
            prog("failed", 100, message=f"ALE framework root invalid: {framework_root}")
            return 2

        prog("starting", 5, counts={"total": len(expected_ids)})

        if not check_ale_venv(framework_root):
            # 不降级 skip：venv 缺即 failed，不产出 verified summary
            prog("failed", 100, message="ALE venv not ready: run `uv sync` in framework root")
            return 3

        request_path = run_dir / "request.json"
        plan_path = run_dir / "plan.json"
        request_path.write_text(json.dumps(request, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        batch_plan = {
            "run_dir": str(run_dir),
            "framework_root": str(framework_root),
            "tasks": tasks_contract,          # exact 契约：codex 必须生成这些 id
            "stages": ["brief", "draft", "scaffold", "oracle_validate"],
            "requirements": {"oracle_must_pass": True, "no_stage2_model_evaluation": True},
        }
        plan_path.write_text(json.dumps(batch_plan, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

        prog("codex_running", 20)
        exit_code = run_codex(plan_path, run_dir, Path.cwd(), framework_root)

        if exit_code != 0:
            prog("failed", 100, message=f"codex exit code {exit_code}")
            return exit_code

        # exact 契约校验：生成且仅生成 expected_ids
        try:
            check_exact_task_ids(run_dir, expected_ids)
        except ValueError as e:
            prog("failed", 100, message=f"task id contract violation: {e}")
            return 4

        prog("oracle_validating", 70)
        # 复用现有三层校验（request 仅用于 fallback task_id 命名，无副作用）
        from dataclasses import dataclass as _dc
        req = TaskRequest(domain=request.get("domain", ""), task_id="",
                          title="", scenario=request.get("scenario", ""),
                          difficulty=request.get("difficulty", "medium"),
                          input_mode="", output_mode="", verification_mode="",
                          reference_strategy="", framework_root=str(framework_root))
        oracle_checks = validate_generated_tasks(run_dir, framework_root, req)
        summary = write_summary(run_dir, req, trigger.get("run_id", ""), exit_code, oracle_checks)

        counts = summary.get("oracle_validation", {}).get("counts", {})
        failed = counts.get("failed", 0)
        prog("done", 100, counts=counts,
             message=f"verified={counts.get('verified',0)} blocked={counts.get('blocked',0)} failed={failed}")
        return 1 if failed > 0 else 0
    except Exception as exc:
        prog("failed", 100, message=f"runner crashed: {type(exc).__name__}: {exc}")
        raise
```
> 注：顶部需 `from ale_progress import write_progress`（Step 13）。`TaskRequest` 已在文件中定义（`:32`）；本处因批量无需单 task_id，传空串占位。

- [ ] **Step 12: 删除旧的 `--skip-oracle-validation` 自动降级（`:598-607` 参数 + `:614-632` 降级逻辑）**

在 `main` 已被整体替换后，旧参数 `--skip-oracle-validation`、`--task-id`、`--domain` 等单任务参数随之移除（它们只存在于旧 main）。确认 `grep -n "skip_oracle_validation\|--task-id" tools/ale-task-factory/scripts/ale_stage1_runner.py` 仅剩 `validate_generated_tasks` 内部无引用即可（应为空）。

- [ ] **Step 13: 加 import + 清理**

在 `ale_stage1_runner.py` import 区加：
```python
from ale_progress import write_progress
```
删除 `run_codex` 旧版里对 `codex.log` 的 `open()` / `with log_path.open(...)`（已在 Step 9 整体替换，确认无残留）：
```bash
grep -n "codex.log\|log_path.open" tools/ale-task-factory/scripts/ale_stage1_runner.py
```
Expected: 空（无残留）。

- [ ] **Step 14: 跑全部测试 + 手动冒烟**

Run: `pytest tools/ale-task-factory/scripts/ -v`
Expected: PASS（ale_progress 3 + stage2 5 + stage1 6 = 14 passed）

手动冒烟（venv 缺失路径）：
```bash
mkdir -p /tmp/ale-s1 && echo '{"type":"stage1","run_id":1,"run_dir":"/tmp/ale-s1","stage1":{"framework_root":"/nonexistent","codex_model":"gpt-5.5","tasks":[{"task_id":"d/t01","title":"T1"}],"request":{"domain":"d","scenario":"s","difficulty":"easy"}}}' > /tmp/trig1.json
python3 tools/ale-task-factory/scripts/ale_stage1_runner.py --run-dir /tmp/ale-s1 --from-trigger /tmp/trig1.json
cat /tmp/ale-s1/stage1_progress.json  # 期望 phase=failed, message 含 invalid framework root
```

- [ ] **Step 15: Commit**

```bash
git add tools/ale-task-factory/scripts/ale_stage1_runner.py tools/ale-task-factory/scripts/ale_stage1_runner_test.py
git commit -m "feat(ale): stage1 runner batch + exact task contract + no-oracle-downgrade + stage1_progress"
```

---

## Self-Review

**1. Spec 覆盖：**
- §4.3 按阶段隔离 progress → Task 1（writer）+ Task 2/3 写 `stage{1,2}_progress.json` ✓
- §6.3 stage2 verified-only + 删 fallback + from-trigger + stdout → Task 2 ✓
- §6.3 stage1 批量 + run-dir + from-trigger + exact IDs + 删降级 + stdout → Task 3 ✓
- §8 runner 是唯一业务终态写入者（try/except 写 failed）→ Task 2 Step 11 / Task 3 Step 11 ✓
- 尚未覆盖（属后续 Plan）：后端 gateway dispatch 前重置 progress、daemon 兜底、Dockerfile/compose、部署文档 → 已声明在 Plan 2/3。

**2. 占位符扫描：** 无 TBD/TODO；每步含真实测试代码与实现代码。

**3. 类型/签名一致性：**
- `write_progress(path, *, stage, phase, percent, counts=, current_task=, message=)` —— Task 1 定义，Task 2/3 调用一致 ✓
- `load_trigger(path) -> dict` —— 两 runner 各自定义（stage1/stage2 校验不同），签名一致 ✓
- `check_exact_task_ids(output_dir, expected_ids)` / `check_ale_venv(framework_root)` —— Task 3 定义并测试 ✓
- `run_codex` 返回 int（Task 3 Step 9 改动），main 用 `exit_code = run_codex(...)` 一致 ✓
- `run_one_task` 透传 stdout（Task 2 Step 9），main 不读 `proc.stderr` ✓

**4. 已知简化（非占位）：** progress 不含 `ts`（writer 无法调 `datetime.now()`；判定不依赖 ts）；runner 单元测试不验 `stage*.log`（日志由 daemon 重定向，见 spec §4.4 / §10）。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-23-ale-runner-contract.md`. 本计划是 3 计划系列的第 1 个。两种执行选项：

**1. Subagent-Driven（推荐）** — 每个 Task 派一个 fresh subagent，任务间 review，迭代快。

**2. Inline Execution** — 本会话内用 executing-plans 批量执行，带 checkpoint。

请选择执行方式；同时确认是否按此切分（Plan 1 runner → Plan 2 后端 → Plan 3 daemon+部署）推进。
