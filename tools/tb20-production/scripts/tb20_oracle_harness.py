#!/usr/bin/env python3
"""Collect standard Harbor oracle-harness evidence for local TB2.0 tasks."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_HARBOR = Path("/home/ubuntu/tb20-runtime/.venv/bin/harbor")


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def discover_tasks(root: Path) -> list[Path]:
    return sorted(path.parent for path in root.rglob("task.toml"))


def rel_task(root: Path, task: Path) -> str:
    return task.relative_to(root).as_posix()


def safe_name(rel: str) -> str:
    value = rel.replace("/", "__").replace(" ", "_")
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def selected_tasks(root: Path, selected: list[str]) -> list[Path]:
    if not selected:
        return discover_tasks(root)
    tasks = []
    all_tasks = discover_tasks(root)
    for item in selected:
        path = root / item
        if path.is_dir():
            tasks.append(path)
        else:
            tasks.extend(task for task in all_tasks if task.name == item or rel_task(root, task) == item)
    return sorted(set(tasks))


def newest_trial_root(job_root: Path) -> Path | None:
    trials = [path.parent for path in job_root.glob("*/result.json") if path.parent.is_dir()]
    return max(trials, key=lambda path: (path / "result.json").stat().st_mtime) if trials else None


def copy_if_file(src: Path, dest: Path) -> bool:
    if not src.is_file():
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        dest.unlink()
    shutil.copy2(src, dest)
    return True


def read_reward(path: Path) -> str:
    if not path.is_file():
        return "0"
    return path.read_text(encoding="utf-8", errors="replace").strip()


def write_ctrf(dest: Path, reward: str, stdout: str) -> None:
    passed = reward in {"1", "1.0"}
    payload = {
        "results": {
            "summary": {"tests": 1, "passed": 1 if passed else 0, "failed": 0 if passed else 1, "skipped": 0},
            "tests": [
                {
                    "name": "oracle-verifier",
                    "status": "passed" if passed else "failed",
                    "message": stdout[-4000:] if stdout else "verifier completed with no stdout/stderr",
                }
            ],
        }
    }
    write_json(dest, payload)


def run_one(root: Path, task: Path, jobs_dir: Path, harbor: Path) -> dict:
    rel = rel_task(root, task)
    logs = task / "oracle-logs"
    logs.mkdir(parents=True, exist_ok=True)
    job_name = "oracle__" + safe_name(rel)
    job_root = jobs_dir / job_name
    if job_root.exists():
        shutil.rmtree(job_root)
    command = [
        str(harbor),
        "run",
        "-p",
        str(task),
        "-a",
        "oracle",
        "--jobs-dir",
        str(jobs_dir),
        "--job-name",
        job_name,
        "--yes",
        "--force-build",
        "--no-delete",
    ]
    started = now()
    with (logs / "harness.log").open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        proc = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, text=True)
    finished = now()
    trial_root = newest_trial_root(job_root)
    copied = {}
    if trial_root:
        copied["harnessRun"] = copy_if_file(trial_root / "result.json", logs / "harness-run.json")
        copied["harnessOracle"] = copy_if_file(trial_root / "agent/oracle.txt", logs / "harness-oracle.txt")
        copied["testStdout"] = copy_if_file(trial_root / "verifier/test-stdout.txt", logs / "test-stdout.txt")
        copied["reward"] = copy_if_file(trial_root / "verifier/reward.txt", logs / "reward.txt")
    else:
        copied["harnessRun"] = False
        copied["harnessOracle"] = False
        copied["testStdout"] = False
        copied["reward"] = False
    copied["harnessJob"] = copy_if_file(job_root / "result.json", logs / "harness-job.json")
    reward = read_reward(logs / "reward.txt")
    stdout = (logs / "test-stdout.txt").read_text(encoding="utf-8", errors="replace") if (logs / "test-stdout.txt").exists() else ""
    if not (logs / "test-stdout.txt").exists():
        (logs / "test-stdout.txt").write_text("verifier completed with no stdout/stderr\n", encoding="utf-8")
    elif not (logs / "test-stdout.txt").read_text(encoding="utf-8", errors="replace").strip():
        (logs / "test-stdout.txt").write_text("verifier completed with no stdout/stderr\n", encoding="utf-8")
    if not (logs / "harness-oracle.txt").exists():
        (logs / "harness-oracle.txt").write_text("oracle agent completed with no stdout/stderr\n", encoding="utf-8")
    elif not (logs / "harness-oracle.txt").read_text(encoding="utf-8", errors="replace").strip():
        (logs / "harness-oracle.txt").write_text("oracle agent completed with no stdout/stderr\n", encoding="utf-8")
    write_ctrf(logs / "ctrf.json", reward, stdout)
    existing = {}
    if (logs / "result.json").is_file():
        try:
            existing = json.loads((logs / "result.json").read_text(encoding="utf-8"))
        except Exception:
            existing = {}
    existing["standardHarness"] = {
        "command": command,
        "jobName": job_name,
        "jobsDir": str(jobs_dir.resolve()),
        "trialRoot": str(trial_root.resolve()) if trial_root else None,
        "startedAt": started,
        "finishedAt": finished,
        "exitCode": proc.returncode,
        "reward": reward,
        "copied": copied,
    }
    existing["ok"] = bool(existing.get("ok", True)) and proc.returncode == 0 and reward in {"1", "1.0"}
    write_json(logs / "result.json", existing)
    return {
        "task": rel,
        "jobName": job_name,
        "exitCode": proc.returncode,
        "reward": reward,
        "trialRoot": str(trial_root) if trial_root else None,
        "ok": proc.returncode == 0 and reward in {"1", "1.0"} and all(copied.values()),
        "copied": copied,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--jobs-dir", type=Path)
    parser.add_argument("--harbor", type=Path, default=DEFAULT_HARBOR)
    parser.add_argument("--task", action="append", default=[])
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    jobs_dir = (args.jobs_dir or root.parent / "oracle-harness-jobs").resolve()
    jobs_dir.mkdir(parents=True, exist_ok=True)
    results = [run_one(root, task, jobs_dir, args.harbor) for task in selected_tasks(root, args.task)]
    evidence = {"root": str(root), "jobsDir": str(jobs_dir), "taskCount": len(results), "results": results, "ok": bool(results) and all(item["ok"] for item in results)}
    if args.evidence:
        write_json(args.evidence.resolve(), evidence)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if evidence["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
