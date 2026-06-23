#!/usr/bin/env python3
"""ALE Stage 1 Runner — generate tasks and programmatically validate oracle evidence.

Flow:
  1. Validate the ALE framework root
  2. Write request.json + plan.json
  3. Run ``codex exec`` with the ``ale-task-factory`` skill to generate task packages
  4. **Programmatic oracle validation** (NEW):
     Level 1 ─ ``uv run python -m ale_run run <exp>.yaml --dry-run`` (config + task listing)
     Level 2 ─ ``TaskLoader.load()`` (main.py importable, no syntax errors)
     Level 3 ─ ``oracle-evidence.json`` schema check (score >= 1.0 or blocked)
  5. Write enhanced ``summary.json`` with per-task status, oracle scores, and evidence paths
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from ale_progress import write_progress

# ── data classes ────────────────────────────────────────────────────────────


@dataclass
class TaskRequest:
    domain: str
    task_id: str
    title: str
    scenario: str
    difficulty: str
    input_mode: str
    output_mode: str
    verification_mode: str
    reference_strategy: str
    framework_root: str
    note: str = ""


@dataclass
class TaskResult:
    task_id: str
    domain: str
    status: str
    score: float | None = None
    reason: str | None = None
    task_dir: str | None = None
    evidence_path: str | None = None


@dataclass
class OracleCheck:
    """Result of one oracle validation for a single task."""

    task_id: str
    status: str  # "verified" | "blocked" | "failed"
    oracle_score: float | None = None
    evidence_path: str | None = None
    dry_run_ok: bool = False
    dry_run_output: str = ""
    task_loader_ok: bool = False
    task_loader_error: str = ""
    evidence_ok: bool = False
    evidence_error: str = ""
    blocked_reason: str = ""


# ── plan builder ─────────────────────────────────────────────────────────────


def build_plan(request: TaskRequest) -> dict[str, Any]:
    task_dir = Path("tasks") / request.domain / request.task_id
    return {
        "task": asdict(request),
        "task_dir": str(task_dir),
        "framework_root": request.framework_root,
        "framework_tasks_root": str(Path(request.framework_root) / "tasks"),
        "stages": [
            "brief",
            "draft",
            "scaffold",
            "oracle_validate",
        ],
        "requirements": {
            "oracle_must_pass": True,
            "no_stage2_model_evaluation": True,
            "ale_native_main_py": True,
            "reference_hidden": True,
        },
        # NEW: runner will execute programmatic validation after Codex
        "post_codex_validation": {
            "enabled": True,
            "levels": ["ale_dry_run", "task_loader", "oracle_evidence"],
        },
    }


# ── codex invocation ─────────────────────────────────────────────────────────


def run_codex(
    plan_path: Path, output_dir: Path, cwd: Path, framework_root: Path
) -> int:
    """codex exec：输出透传到父进程 stdout/stderr（daemon 重定向到 stage1.log）。返回退出码。"""
    output_dir.mkdir(parents=True, exist_ok=True)
    cmd = [
        "codex",
        "exec",
        "--cd",
        str(cwd),
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
        cmd,
        cwd=cwd,
        env=env,
        stdout=sys.stdout,
        stderr=sys.stderr,
        text=True,
        check=False,
    )
    return proc.returncode


# ── oracle validation ────────────────────────────────────────────────────────


def _find_uv() -> str:
    """Locate the ``uv`` binary (prefer what is on PATH, then common locations)."""
    for candidate in ("uv", os.path.expanduser("~/.local/bin/uv"), os.path.expanduser("~/.cargo/bin/uv")):
        if subprocess.run(["which", candidate], capture_output=True, text=True).returncode == 0:
            return candidate
    return "uv"  # fallback


def check_ale_venv(framework_root: Path) -> bool:
    """返回 ALE venv 是否就绪；不在此处副作用，由 main 决定写 failed（绝不降级 skip）。"""
    uv = _find_uv()
    result = subprocess.run(
        [uv, "run", "python", "-c", "import cua_bench"],
        cwd=str(framework_root), capture_output=True, text=True,
        timeout=30, check=False,
    )
    return result.returncode == 0


def run_ale_dry_run(
    framework_root: Path,
    task_path: str,
    *,
    agent_config: str = "configs/agents/dummy.yaml",
    env_config: str = "configs/environments/docker.yaml",
) -> dict:
    """Level 1 ─ Run ``ale_run --dry-run`` to validate experiment config + task listing.

    Returns ``{ok: bool, output: str, error: str|None}``.
    """
    uv = _find_uv()
    task_file = framework_root.parent / "tmp_dry_run_tasks.txt" if framework_root.parent.exists() else Path(tempfile.gettempdir()) / "tmp_dry_run_tasks.txt"
    task_file.parent.mkdir(parents=True, exist_ok=True)
    task_file.write_text(task_path + "\n", encoding="utf-8")

    exp_yaml = framework_root.parent / "tmp_dry_run_exp.yaml" if framework_root.parent.exists() else Path(tempfile.gettempdir()) / "tmp_dry_run_exp.yaml"
    exp_content = f"""name: oracle_dry_run
agents:
  - {framework_root / agent_config}
environment: {framework_root / env_config}
tasks: {task_file}
output:
  root: {framework_root / '.logs' / 'ale'}
"""
    exp_yaml.write_text(exp_content, encoding="utf-8")

    try:
        result = subprocess.run(
            [uv, "run", "python", "-m", "ale_run", "run", str(exp_yaml), "--dry-run"],
            cwd=str(framework_root),
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
        output = result.stdout + result.stderr
        task_listed = task_path.strip("/") in output
        ok = result.returncode == 0 and task_listed
        error = None if ok else f"exit={result.returncode}, task_in_output={task_listed}"
        return {"ok": ok, "output": output, "error": error}
    except subprocess.TimeoutExpired:
        return {"ok": False, "output": "", "error": "ALE dry-run timed out (120s)"}
    except Exception as exc:
        return {"ok": False, "output": "", "error": str(exc)}
    finally:
        # Clean up temp files
        for p in (task_file, exp_yaml):
            try:
                p.unlink(missing_ok=True)
            except OSError:
                pass


def run_task_loader_check(framework_root: Path, task_rel_path: str) -> dict:
    """Level 2 ─ Actually import the task via ``TaskLoader.load()``.

    Uses ``uv run python`` so the venv with ``cua_bench`` is active.

    Returns ``{ok: bool, description: str, error: str|None}``.
    """
    uv = _find_uv()
    # strip leading "tasks/" if present — TaskLoader expects the bare path
    loader_path = task_rel_path.removeprefix("tasks/").removeprefix("/")
    loader_path = "tasks/" + loader_path

    script = f"""
import sys
try:
    from ale_run.tasks.loader import TaskLoader
    loader = TaskLoader("{loader_path}")
    info = loader.load(0)
    desc = info.get("description", "")[:100] if isinstance(info, dict) else str(info)[:100]
    print("OK:" + desc)
except Exception as exc:
    print("FAIL:" + type(exc).__name__ + ":" + str(exc))
"""
    try:
        result = subprocess.run(
            [uv, "run", "python", "-c", script],
            cwd=str(framework_root),
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        output = (result.stdout + result.stderr).strip()
        if output.startswith("OK:"):
            return {"ok": True, "description": output[3:], "error": None}
        else:
            return {"ok": False, "description": "", "error": output.removeprefix("FAIL:")}
    except subprocess.TimeoutExpired:
        return {"ok": False, "description": "", "error": "TaskLoader check timed out (60s)"}
    except Exception as exc:
        return {"ok": False, "description": "", "error": str(exc)}


def validate_oracle_evidence(task_dir: Path, task_id: str, task_name: str) -> dict:
    """Level 3 ─ Validate ``oracle-logs/oracle-evidence.json``.

    Returns ``{ok: bool, score: float|None, status: str, blocked_reason: str|None, error: str|None}``.
    """
    evidence_path = task_dir / "oracle-logs" / "oracle-evidence.json"
    if not evidence_path.is_file():
        return {
            "ok": False,
            "score": None,
            "status": "failed",
            "blocked_reason": None,
            "error": f"Missing oracle-evidence.json at {evidence_path}",
        }

    try:
        data = json.loads(evidence_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return {
            "ok": False,
            "score": None,
            "status": "failed",
            "blocked_reason": None,
            "error": f"Invalid JSON in oracle-evidence.json: {exc}",
        }

    # Validate schema
    evidence_task_id = data.get("task_id", "")
    evidence_status = data.get("status", "")
    oracle = data.get("oracle", {}) if isinstance(data.get("oracle"), dict) else {}
    oracle_score = oracle.get("score")
    blocked_reason = data.get("blocked_reason")

    errors = []
    full_task_id = f"{task_id}/{task_name}"

    if evidence_task_id and evidence_task_id != full_task_id:
        errors.append(f"task_id mismatch: expected '{full_task_id}', got '{evidence_task_id}'")

    if evidence_status == "blocked":
        if not blocked_reason:
            errors.append("status is 'blocked' but blocked_reason is empty")
        return {
            "ok": len(errors) == 0,
            "score": None,
            "status": "blocked",
            "blocked_reason": blocked_reason,
            "error": "; ".join(errors) if errors else None,
        }

    if evidence_status == "verified":
        if oracle_score is None:
            errors.append("status is 'verified' but oracle.score is missing")
        elif not isinstance(oracle_score, (int, float)):
            errors.append(f"oracle.score must be numeric, got {type(oracle_score).__name__}")
        elif oracle_score < 1.0:
            errors.append(f"oracle.score is {oracle_score}, expected >= 1.0")
        return {
            "ok": len(errors) == 0,
            "score": oracle_score if isinstance(oracle_score, (int, float)) else None,
            "status": "verified",
            "blocked_reason": None,
            "error": "; ".join(errors) if errors else None,
        }

    errors.append(f"unknown status '{evidence_status}', expected 'verified' or 'blocked'")
    return {
        "ok": False,
        "score": None,
        "status": "failed",
        "blocked_reason": None,
        "error": "; ".join(errors),
    }


def _discover_task_dirs(output_dir: Path) -> list[tuple[str, str, Path]]:
    """Find all generated task directories under ``output_dir/tasks/``.

    Returns list of ``(domain, task_name, task_dir)`` tuples.
    """
    tasks_root = output_dir / "tasks"
    if not tasks_root.is_dir():
        return []
    discovered = []
    for domain_dir in sorted(tasks_root.iterdir()):
        if not domain_dir.is_dir():
            continue
        for task_dir in sorted(domain_dir.iterdir()):
            if task_dir.is_dir() and (task_dir / "task_card.json").is_file():
                discovered.append((domain_dir.name, task_dir.name, task_dir))
    return discovered


def validate_generated_tasks(
    output_dir: Path,
    framework_root: Path,
    request: TaskRequest,
) -> list[OracleCheck]:
    """Run all three validation levels against every task generated by Codex.

    Returns one ``OracleCheck`` per task, ordered by domain+task_name.
    """
    task_dirs = _discover_task_dirs(output_dir)
    if not task_dirs:
        return [
            OracleCheck(
                task_id=f"{request.domain}/{request.task_id}",
                status="failed",
                blocked_reason="No generated task directories found under output_dir/tasks/",
            )
        ]

    results: list[OracleCheck] = []
    for domain, task_name, task_dir in task_dirs:
        task_id = f"{domain}/{task_name}"
        rel_path = str(task_dir.relative_to(output_dir))

        check = OracleCheck(task_id=task_id, status="failed")

        # Level 1 ─ ALE dry-run
        dry_result = run_ale_dry_run(framework_root, f"{domain}/{task_name}")
        check.dry_run_ok = dry_result["ok"]
        check.dry_run_output = dry_result.get("output", "")

        # Level 2 ─ TaskLoader
        # The task is under output_dir/tasks/<domain>/<task_name>, not in
        # framework_root/tasks/.  Copy / symlink so TaskLoader can find it.
        loader_result = _run_task_loader_for_output_task(
            framework_root, output_dir, domain, task_name
        )
        check.task_loader_ok = loader_result["ok"]
        check.task_loader_error = loader_result.get("error", "")

        # Level 3 ─ Oracle evidence
        evidence_result = validate_oracle_evidence(task_dir, domain, task_name)
        check.evidence_ok = evidence_result["ok"]
        check.evidence_error = evidence_result.get("error", "")
        check.oracle_score = evidence_result.get("score")
        check.evidence_path = str(task_dir / "oracle-logs" / "oracle-evidence.json")
        check.blocked_reason = evidence_result.get("blocked_reason")

        # Determine overall status
        if evidence_result["status"] == "blocked":
            check.status = "blocked"
        elif (
            check.dry_run_ok and check.task_loader_ok and check.evidence_ok
        ):
            check.status = "verified"
        else:
            check.status = "failed"

        results.append(check)

    return results


def _run_task_loader_for_output_task(
    framework_root: Path,
    output_dir: Path,
    domain: str,
    task_name: str,
) -> dict:
    """Run TaskLoader against a task that lives under *output_dir*/tasks/ rather
    than the ALE framework tree.

    Strategy: create a symlink inside the framework's ``tasks/`` tree so
    TaskLoader can resolve it, then clean up.
    """
    uv = _find_uv()
    src = output_dir / "tasks" / domain / task_name
    link = framework_root / "tasks" / domain / task_name

    if not (src / "main.py").is_file():
        return {"ok": False, "error": f"main.py not found at {src}"}

    # Ensure the domain directory exists in the framework
    (framework_root / "tasks" / domain).mkdir(parents=True, exist_ok=True)

    # Remove stale link if present
    if link.is_symlink() or link.exists():
        try:
            link.unlink()
        except OSError:
            pass

    try:
        link.symlink_to(src, target_is_directory=True)
    except OSError as exc:
        # Fallback: copy main.py + task_card.json into a temp location
        tmp = framework_root / "tasks" / domain / f"_tmp_oracle_{task_name}"
        tmp.mkdir(parents=True, exist_ok=True)
        _copy_task_files(src, tmp)
        loader_path = f"tasks/{domain}/_tmp_oracle_{task_name}"
        result = _invoke_task_loader(uv, framework_root, loader_path)
        _rmtree_quiet(tmp)
        return result

    try:
        loader_path = f"tasks/{domain}/{task_name}"
        return _invoke_task_loader(uv, framework_root, loader_path)
    finally:
        try:
            link.unlink()
        except OSError:
            pass


def _copy_task_files(src: Path, dst: Path) -> None:
    """Copy ``main.py`` (and ``task_card.json`` if present) from *src* to *dst*."""
    for name in ("main.py", "task_card.json"):
        s = src / name
        if s.is_file():
            dst.mkdir(parents=True, exist_ok=True)
            (dst / name).write_bytes(s.read_bytes())


def _invoke_task_loader(uv: str, framework_root: Path, loader_path: str) -> dict:
    """Call ``TaskLoader.load()`` via ``uv run python`` and return ``{ok, error}``."""
    script = f"""
import sys
try:
    from ale_run.tasks.loader import TaskLoader
    loader = TaskLoader("{loader_path}")
    info = loader.load(0)
    desc = info.get("description", "")[:100] if isinstance(info, dict) else str(info)[:100]
    print("OK:" + desc)
except Exception as exc:
    print("FAIL:" + type(exc).__name__ + ":" + str(exc))
"""
    try:
        result = subprocess.run(
            [uv, "run", "python", "-c", script],
            cwd=str(framework_root),
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        output = (result.stdout + result.stderr).strip()
        if output.startswith("OK:"):
            return {"ok": True, "error": ""}
        return {"ok": False, "error": output.removeprefix("FAIL:")}
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "TaskLoader check timed out (60s)"}
    except Exception as exc:
        return {"ok": False, "error": str(exc)}


def _rmtree_quiet(path: Path) -> None:
    """Best-effort recursive delete."""
    import shutil

    try:
        shutil.rmtree(str(path))
    except OSError:
        pass


# ── summary writer ───────────────────────────────────────────────────────────


def write_summary(
    output_root: Path,
    request: TaskRequest,
    run_id: str,
    codex_exit_code: int,
    oracle_checks: list[OracleCheck],
) -> dict:
    """Build and persist the enhanced ``summary.json``."""

    task_summaries = []
    verified = blocked = failed = 0
    for chk in oracle_checks:
        if chk.status == "verified":
            verified += 1
        elif chk.status == "blocked":
            blocked += 1
        else:
            failed += 1
        task_summaries.append(
            {
                "task_id": chk.task_id,
                "status": chk.status,
                "oracle_score": chk.oracle_score,
                "evidence_path": chk.evidence_path,
                "dry_run_ok": chk.dry_run_ok,
                "task_loader_ok": chk.task_loader_ok,
                "evidence_ok": chk.evidence_ok,
                "blocked_reason": chk.blocked_reason,
                "errors": [e for e in (chk.task_loader_error, chk.evidence_error) if e],
            }
        )

    overall = "completed"
    if not oracle_checks:
        overall = "failed"
    elif blocked == len(oracle_checks):
        overall = "blocked"
    elif failed > 0:
        overall = "partial"

    summary = {
        "run_id": run_id,
        "domain": request.domain,
        "task_id": request.task_id,
        "status": overall,
        "task_dir": str(Path("tasks") / request.domain / request.task_id),
        "codex_exit_code": codex_exit_code,
        "oracle_validation": {
            "by_task": task_summaries,
            "counts": {
                "verified": verified,
                "blocked": blocked,
                "failed": failed,
                "total": len(task_summaries),
            },
        },
        "outputs": [
            str(output_root / "request.json"),
            str(output_root / "plan.json"),
            str(output_root / "codex.log"),
        ],
    }
    (output_root / "summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    return summary


# ── trigger / contract helpers ────────────────────────────────────────────────


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


# ── main ─────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(description="ALE Stage 1 Runner (batch)")
    parser.add_argument("--run-dir", required=True, help="output root for this run")
    parser.add_argument("--from-trigger", required=True, help="trigger json path")
    args = parser.parse_args()

    run_dir = Path(args.run_dir).expanduser().resolve()
    trigger = load_trigger(Path(args.from_trigger).expanduser().resolve())
    s1 = trigger["stage1"]
    framework_root = Path(s1["framework_root"]).expanduser().resolve()
    tasks_contract = s1["tasks"]
    request = s1.get("request", {})
    expected_ids = [t["task_id"] for t in tasks_contract]

    progress = run_dir / "stage1_progress.json"
    def prog(phase, percent, **kw):
        write_progress(progress, stage="stage1", phase=phase, percent=percent, **kw)

    try:
        if not run_dir.is_dir():
            prog("failed", 100, message=f"run_dir not found: {run_dir}")
            return 2
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
        req = TaskRequest(domain=request.get("domain", ""), task_id="",
                          title="", scenario=request.get("scenario", ""),
                          difficulty=request.get("difficulty", "medium"),
                          input_mode="", output_mode="", verification_mode="",
                          reference_strategy="", framework_root=str(framework_root))
        oracle_checks = validate_generated_tasks(run_dir, framework_root, req)
        summary = write_summary(run_dir, req, str(trigger.get("run_id", "")), exit_code, oracle_checks)

        counts = summary.get("oracle_validation", {}).get("counts", {})
        failed = counts.get("failed", 0)
        prog("done", 100, counts=counts,
             message=f"verified={counts.get('verified',0)} blocked={counts.get('blocked',0)} failed={failed}")
        return 1 if failed > 0 else 0
    except Exception as exc:
        prog("failed", 100, message=f"runner crashed: {type(exc).__name__}: {exc}")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
