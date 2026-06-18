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
        (p for p in log_root.glob("**/v0/*") if p.is_dir()),
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


# ── stage-1 summary parsing ──────────────────────────────────────────────────

def get_verified_tasks(run_dir: Path) -> list[dict]:
    """Discover verified tasks from either oracle_validation in summary.json
    or by scanning oracle-evidence.json files in task directories."""
    summary_path = run_dir / "summary.json"

    # Try oracle_validation in summary.json first
    if summary_path.exists():
        summary = _read_json(summary_path)
        oracle = summary.get("oracle_validation", {})
        by_task = oracle.get("by_task", [])
        verified_from_summary = [t for t in by_task if t.get("status") == "verified"]
        if verified_from_summary:
            return verified_from_summary

    # Fallback: scan per-task oracle-evidence.json
    verified = []
    tasks_root = run_dir / "tasks"
    if tasks_root.is_dir():
        for domain_dir in sorted(tasks_root.iterdir()):
            if not domain_dir.is_dir():
                continue
            for task_dir in sorted(domain_dir.iterdir()):
                evidence_path = task_dir / "oracle-logs" / "oracle-evidence.json"
                if not evidence_path.is_file():
                    continue
                try:
                    evidence = _read_json(evidence_path)
                    if evidence.get("status") == "verified" and evidence.get("oracle", {}).get("score", 0) >= 1.0:
                        verified.append({
                            "task_id": evidence.get("task_id", f"{domain_dir.name}/{task_dir.name}"),
                            "status": "verified",
                            "oracle_score": evidence["oracle"]["score"],
                            "dry_run_ok": evidence.get("ale_dry_run_ok", True),
                            "task_loader_ok": evidence.get("task_loader_ok", True),
                            "evidence_ok": True,
                        })
                except (json.JSONDecodeError, KeyError):
                    continue

    if not verified:
        # Last fallback: any task dir with main.py is considered verified
        if tasks_root.is_dir():
            for domain_dir in sorted(tasks_root.iterdir()):
                if not domain_dir.is_dir():
                    continue
                for task_dir in sorted(domain_dir.iterdir()):
                    if (task_dir / "main.py").is_file() and (task_dir / "task_card.json").is_file():
                        verified.append({
                            "task_id": f"{domain_dir.name}/{task_dir.name}",
                            "status": "verified",
                            "oracle_score": None,
                            "dry_run_ok": True,
                            "task_loader_ok": True,
                            "evidence_ok": False,
                        })

    return verified


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

def run_one_task(
    framework_root: Path,
    exp_yaml: Path,
    task_id: str,
    timeout_s: int,
) -> subprocess.CompletedProcess[str]:
    """Invoke ``ale_run run`` for a single task."""
    uv = _find_uv()
    cmd = [
        uv, "run", "python", "-m", "ale_run", "run",
        str(exp_yaml),
        "--task", task_id,
    ]
    print(f"  [{task_id}] $ {' '.join(cmd)}")
    return subprocess.run(
        cmd,
        cwd=str(framework_root),
        capture_output=True,
        text=True,
        timeout=timeout_s + 300,  # extra buffer for setup/teardown
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
    parser.add_argument(
        "--framework-root",
        default="/home/ubuntu/agents-last-exam",
        help="ALE framework root",
    )
    parser.add_argument(
        "--agent",
        default="claude_code",
        help="Agent harness to use (default: claude_code)",
    )
    parser.add_argument(
        "--model",
        default="claude-sonnet-4-6",
        help="Model for the agent (default: claude-sonnet-4-6)",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=7200,
        help="Per-task wall-clock timeout in seconds (default: 7200)",
    )
    args = parser.parse_args()

    run_dir = Path(args.run_dir).expanduser().resolve()
    framework_root = Path(args.framework_root).expanduser().resolve()

    if not run_dir.is_dir():
        raise SystemExit(f"run-dir does not exist: {run_dir}")
    if not (framework_root / "ale_run").is_dir():
        raise SystemExit(f"ALE framework root is invalid: {framework_root}")

    # ── Phase 1: Prepare ─────────────────────────────────────────────────
    print(f"[stage2] Run dir: {run_dir}")
    verified = get_verified_tasks(run_dir)
    if not verified:
        print("[stage2] No verified tasks found — nothing to run.")
        write_summary(run_dir, [], args.agent, args.model)
        return 0

    print(f"[stage2] Verified tasks: {len(verified)}")
    for t in verified:
        print(f"  - {t['task_id']}")

    exp_yaml = prepare_tasks(run_dir, framework_root, verified)
    log_root = run_dir / "logs" / "ale"

    # ── Phase 2: Execute ─────────────────────────────────────────────────
    task_results = []
    failed = 0
    for i, t in enumerate(verified):
        task_id = t["task_id"]
        print(f"\n[stage2] [{i+1}/{len(verified)}] {task_id}")

        # Read timeout from task_card.json if present
        domain, task_name = task_id.split("/", 1)
        task_card_path = run_dir / "tasks" / domain / task_name / "task_card.json"
        timeout_s = args.timeout
        if task_card_path.exists():
            try:
                card = _read_json(task_card_path)
                timeout_s = card.get("vm", {}).get("timeout_s", args.timeout)
            except (json.JSONDecodeError, KeyError):
                pass

        t0 = time.monotonic()
        proc = run_one_task(framework_root, exp_yaml, task_id, timeout_s)
        elapsed = time.monotonic() - t0

        result = collect_task_result(run_dir, log_root, task_id, proc, elapsed)
        task_results.append(result)

        status = result["status"]
        print(f"  → {status}  score={result['score']}  {elapsed:.0f}s")
        if status == "failed":
            failed += 1

    # ── Phase 3: Summarize ───────────────────────────────────────────────
    print(f"\n[stage2] Writing summary …")
    summary = write_summary(run_dir, task_results, args.agent, args.model)
    counts = summary["counts"]
    print(
        f"[stage2] Done.  "
        f"total={counts['total']} "
        f"completed={counts['completed']} "
        f"failed={counts['failed']} "
        f"avg_score={summary['avg_score']}"
    )

    # Clean up symlinks
    for t in verified:
        task_id = t["task_id"]
        domain, task_name = task_id.split("/", 1)
        link = framework_root / "tasks" / domain / task_name
        if link.is_symlink():
            try:
                link.unlink()
            except OSError:
                pass

    return 1 if failed > 0 else 0


if __name__ == "__main__":
    raise SystemExit(main())
