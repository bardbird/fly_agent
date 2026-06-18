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
) -> subprocess.CompletedProcess[str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    log_path = output_dir / "codex.log"
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
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"$ {' '.join(cmd)}\n")
        log.flush()
        proc = subprocess.run(
            cmd,
            cwd=cwd,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
    return proc


# ── oracle validation ────────────────────────────────────────────────────────


def _find_uv() -> str:
    """Locate the ``uv`` binary (prefer what is on PATH, then common locations)."""
    for candidate in ("uv", os.path.expanduser("~/.local/bin/uv"), os.path.expanduser("~/.cargo/bin/uv")):
        if subprocess.run(["which", candidate], capture_output=True, text=True).returncode == 0:
            return candidate
    return "uv"  # fallback


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


# ── main ─────────────────────────────────────────────────────────────────────


def main() -> int:
    parser = argparse.ArgumentParser(
        description="ALE Stage 1 Runner — generate tasks + programmatic oracle validation"
    )
    parser.add_argument("--domain", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--difficulty", required=True, choices=["easy", "medium", "hard"])
    parser.add_argument("--input-mode", required=True)
    parser.add_argument("--output-mode", required=True)
    parser.add_argument("--verification-mode", required=True)
    parser.add_argument("--reference-strategy", required=True)
    parser.add_argument(
        "--framework-root", default="/Users/liuyifei/Liu/github/agents-last-exam"
    )
    parser.add_argument("--note", default="")
    parser.add_argument("--output-root", required=True)
    parser.add_argument(
        "--skip-oracle-validation",
        action="store_true",
        help="Skip programmatic oracle validation (not recommended)",
    )
    args = parser.parse_args()

    framework_root = Path(args.framework_root).expanduser().resolve()
    if not (framework_root / "ale_run").is_dir() or not (framework_root / "tasks").is_dir():
        raise SystemExit(f"ALE framework root is invalid: {framework_root}")

    # For dry-run / TaskLoader the venv must exist
    uv = _find_uv()
    if not args.skip_oracle_validation:
        uv_check = subprocess.run(
            [uv, "run", "python", "-c", "import cua_bench"],
            cwd=str(framework_root),
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
        if uv_check.returncode != 0:
            print(
                f"⚠  ALE venv may not be ready ({uv} run python -c 'import cua_bench' failed).\n"
                f"   Run `cd {framework_root} && uv sync` first.\n"
                f"   Continuing without oracle validation — run with --skip-oracle-validation to suppress.",
                file=sys.stderr,
            )
            args.skip_oracle_validation = True

    request = TaskRequest(
        domain=args.domain,
        task_id=args.task_id,
        title=args.title,
        scenario=args.scenario,
        difficulty=args.difficulty,
        input_mode=args.input_mode,
        output_mode=args.output_mode,
        verification_mode=args.verification_mode,
        reference_strategy=args.reference_strategy,
        framework_root=str(framework_root),
        note=args.note,
    )

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_root = Path(args.output_root) / f"{args.domain}__{args.task_id}__{run_id}"
    output_root.mkdir(parents=True, exist_ok=True)

    # Phase 1 — write inputs
    request_path = output_root / "request.json"
    plan_path = output_root / "plan.json"
    request_path.write_text(
        json.dumps(asdict(request), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    plan_path.write_text(
        json.dumps(build_plan(request), indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    # Phase 2 — Codex generation
    print(f"[{run_id}] Running codex exec …")
    proc = run_codex(plan_path, output_root, Path.cwd(), framework_root)
    codex_ok = proc.returncode == 0
    print(f"[{run_id}] Codex exited with {proc.returncode} {'✓' if codex_ok else '✗'}")

    # Phase 3 — Oracle validation (NEW)
    oracle_checks: list[OracleCheck] = []
    if args.skip_oracle_validation:
        print(f"[{run_id}] Oracle validation SKIPPED (--skip-oracle-validation)")
    else:
        print(f"[{run_id}] Running oracle validation …")
        oracle_checks = validate_generated_tasks(output_root, framework_root, request)
        for chk in oracle_checks:
            icon = "✓" if chk.status == "verified" else ("⊘" if chk.status == "blocked" else "✗")
            print(
                f"  {icon} {chk.task_id}: {chk.status}"
                + (f" (score={chk.oracle_score})" if chk.oracle_score is not None else "")
                + (f" [blocked: {chk.blocked_reason}]" if chk.blocked_reason else "")
                + (
                    f" [errors: {chk.task_loader_error} {chk.evidence_error}]"
                    if not chk.dry_run_ok or not chk.task_loader_ok or not chk.evidence_ok
                    else ""
                )
            )

    # Phase 4 — Write enhanced summary
    summary = write_summary(output_root, request, run_id, proc.returncode, oracle_checks)
    overall = summary["status"]
    counts = summary.get("oracle_validation", {}).get("counts", {})
    print(
        f"[{run_id}] Done.  "
        f"Codex={'OK' if codex_ok else 'FAIL'},  "
        f"Oracle={overall},  "
        f"verified={counts.get('verified', 0)}, "
        f"blocked={counts.get('blocked', 0)}, "
        f"failed={counts.get('failed', 0)}"
    )

    # Exit code: 0 only if codex passed AND no oracle failures
    if not codex_ok:
        return proc.returncode
    failed_tasks = sum(1 for c in oracle_checks if c.status == "failed")
    return 1 if failed_tasks > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
