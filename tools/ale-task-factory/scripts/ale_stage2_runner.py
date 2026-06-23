#!/usr/bin/env python3
"""ALE Stage 2 Runner — execute verified task packages through the ALE framework.

Flow:
  1. Read stage-1 summary.json → find verified tasks
  2. Symlink tasks into ALE framework
  3. Generate exp.yaml + task list
  4. For each task: ``uv run python -m ale_run run ... --task <id>``
  5. Collect results from ALE output → write per-task result.json
  6. Aggregate → write final summary.json

Usage::

    python3 ale_stage2_runner.py \\
        --run-dir <ale-runs>/<run-id> \\
        --framework-root /home/ubuntu/agents-last-exam
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from ale_progress import write_progress


# ── helpers ──────────────────────────────────────────────────────────────────

def _find_uv() -> str:
    for candidate in ("uv", os.path.expanduser("~/.local/bin/uv")):
        if subprocess.run(["which", candidate], capture_output=True, text=True).returncode == 0:
            return candidate
    return "uv"


def _find_run_dirs(log_root: Path) -> list[Path]:
    """Find all run timestamp dirs under an ALE log tree, newest first."""
    if not log_root.exists():
        return []
    dirs = sorted(
        (p for p in log_root.glob("**/v0/*") if p.is_dir() and (p / "run.json").exists()),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    return dirs


def _read_json(path: Path) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def _write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


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


# ── stage-1 summary parsing ──────────────────────────────────────────────────

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


# ── prepare ──────────────────────────────────────────────────────────────────

def prepare_tasks(
    run_dir: Path,
    framework_root: Path,
    verified_tasks: list[dict],
) -> Path:
    """Symlink verified tasks into framework + write task list + exp.yaml.

    Returns the path to ``exp.yaml``.
    """
    run_key = run_dir.name
    task_list_path = framework_root / "selected_tasks" / f"stage2_{run_key}.txt"

    lines = []
    for t in verified_tasks:
        task_id = t["task_id"]  # "<domain>/<task_name>"
        domain, task_name = task_id.split("/", 1)

        src = run_dir / "tasks" / domain / task_name
        dst = framework_root / "tasks" / domain / task_name

        if not (src / "main.py").exists():
            print(f"  ⚠ skip {task_id}: main.py not found at {src}")
            continue

        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.is_symlink() or dst.exists():
            dst.unlink()
        dst.symlink_to(src.resolve(), target_is_directory=True)
        lines.append(task_id)

    task_list_path.parent.mkdir(parents=True, exist_ok=True)
    task_list_path.write_text("\n".join(lines) + "\n", encoding="utf-8")

    exp_yaml = run_dir / "exp.yaml"
    exp_content = f"""name: ale_stage2_{run_key}
secret_file: {framework_root}/secret/.env
agents:
  - {framework_root}/configs/agents/claude_code.yaml
environment: {framework_root}/configs/environments/docker.yaml
tasks: {task_list_path}
output:
  root: {run_dir}/logs/ale
concurrency: 1
cleanup_mode: delete
# wall_time_s is intentionally omitted — ALE reads timeout_s from each task's metadata
"""
    exp_yaml.write_text(exp_content, encoding="utf-8")
    return exp_yaml


# ── execute ──────────────────────────────────────────────────────────────────

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


# ── collect ──────────────────────────────────────────────────────────────────

def collect_task_result(
    run_dir: Path,
    log_root: Path,
    task_id: str,
    proc: subprocess.CompletedProcess[str],
    elapsed_s: float,
) -> dict:
    """Find ALE run dir for this task and collect artifacts."""
    domain, task_name = task_id.split("/", 1)
    task_result_dir = run_dir / "results" / f"{domain}__{task_name}"
    agent_log_dir = task_result_dir / "agent-log"

    # Best-effort find the ALE run directory
    ale_run_dirs = _find_run_dirs(log_root)
    ale_dir = None
    for d in ale_run_dirs:
        # d is like .../v0/<timestamp> — check its ancestor's task slug
        if task_name.replace("/", "_") in str(d) or domain in str(d.parent.parent):
            ale_dir = d
            break

    result = {
        "task_id": task_id,
        "status": "failed",
        "score": None,
        "duration_s": round(elapsed_s, 1),
        "error": None,
    }

    if ale_dir and ale_dir.exists():
        # Read ALE's run.json
        run_json_path = ale_dir / "run.json"
        if run_json_path.exists():
            try:
                run_data = _read_json(run_json_path)
                result["status"] = run_data.get("status", "failed")
                result["score"] = run_data.get("score")
                if run_data.get("error"):
                    result["error"] = run_data["error"]
            except (json.JSONDecodeError, KeyError):
                pass

        # Copy origin_log → agent-log
        origin_log = ale_dir / "origin_log"
        if origin_log.exists():
            agent_log_dir.mkdir(parents=True, exist_ok=True)
            _copy_tree(origin_log, agent_log_dir)

        # Copy output → agent-log/output
        output_dir = ale_dir / "output"
        if output_dir.exists():
            (agent_log_dir / "output").mkdir(parents=True, exist_ok=True)
            _copy_tree(output_dir, agent_log_dir / "output")

        # Extract shell.log from transcript if present
        _extract_shell_log(agent_log_dir)

    else:
        # No ALE run dir — use process output for error info
        result["status"] = "failed"
        result["error"] = (proc.stderr or proc.stdout or "unknown error")[:2000]

    if proc.returncode != 0 and result["status"] != "completed":
        result["status"] = "failed"
        if not result["error"]:
            result["error"] = f"ale_run exit code {proc.returncode}"

    _write_json(task_result_dir / "result.json", result)
    return result


def _copy_tree(src: Path, dst: Path) -> None:
    """Recursive copy, merging dirs."""
    if not src.exists():
        return
    if src.is_dir():
        dst.mkdir(parents=True, exist_ok=True)
        for child in src.iterdir():
            _copy_tree(child, dst / child.name)
    else:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def _extract_shell_log(agent_log_dir: Path) -> None:
    """Extract shell commands & output from transcript to shell.log."""
    transcript = agent_log_dir / "transcript.jsonl"
    if not transcript.exists():
        # Check under origin_log subdir
        for p in agent_log_dir.glob("**/transcript*.json*"):
            transcript = p
            break
    if not transcript.exists():
        return

    shell_log_path = agent_log_dir / "shell.log"
    try:
        with open(transcript, encoding="utf-8") as f_in, \
             open(shell_log_path, "w", encoding="utf-8") as f_out:
            for line in f_in:
                line = line.strip()
                if not line:
                    continue
                try:
                    entry = json.loads(line)
                except json.JSONDecodeError:
                    continue
                # Write command + output entries
                cmd = entry.get("command") or entry.get("tool_input", {}).get("command", "")
                output = entry.get("output") or entry.get("result", "")
                if cmd:
                    f_out.write(f"$ {cmd}\n")
                if output:
                    f_out.write(f"{output}\n")
                if cmd or output:
                    f_out.write("\n")
    except (OSError, UnicodeDecodeError):
        pass


# ── summarize ────────────────────────────────────────────────────────────────

def write_summary(run_dir: Path, task_results: list[dict], agent: str, model: str) -> dict:
    """Aggregate per-task results into stage2_summary.json (does NOT overwrite stage1 summary.json)."""
    counts = {"total": 0, "completed": 0, "failed": 0, "blocked": 0}
    scores = []
    durations = []

    for r in task_results:
        counts["total"] += 1
        st = r.get("status", "failed")
        if st == "completed":
            counts["completed"] += 1
        elif st in ("blocked", "timeout"):
            counts["blocked"] += 1
        else:
            counts["failed"] += 1

        if r.get("score") is not None:
            scores.append(r["score"])
        if r.get("duration_s") is not None:
            durations.append(r["duration_s"])

    summary = {
        "run_id": run_dir.name,
        "agent": agent,
        "model": model,
        "status": "completed" if counts["failed"] == 0 else "partial",
        "counts": counts,
        "avg_score": round(sum(scores) / len(scores), 4) if scores else None,
        "avg_duration_s": round(sum(durations) / len(durations), 1) if durations else None,
        "results_dir": "results/",
    }
    _write_json(run_dir / "stage2_summary.json", summary)
    return summary


# ── main ─────────────────────────────────────────────────────────────────────

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
            return 0  # 非 runner 崩溃：如实记录，退出 0

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
    except Exception as exc:  # try/except：任何异常写终态 failed
        prog("failed", 100, message=f"runner crashed: {type(exc).__name__}: {exc}")
        raise


if __name__ == "__main__":
    raise SystemExit(main())
