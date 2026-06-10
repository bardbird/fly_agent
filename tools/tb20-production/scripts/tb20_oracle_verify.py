#!/usr/bin/env python3
"""Run oracle solution verification for local TB2.0 task directories.

This script is intentionally separate from Harbor/agent execution. It proves
that the task image, reference solution, and verifier are internally consistent
before target-agent evaluation starts, and it leaves durable per-task logs.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


DIFFICULTIES = {"easy", "medium", "hard"}
REQUIRED = [
    "task.toml",
    "instruction.md",
    "environment/Dockerfile",
    "solution/solve.sh",
    "tests/test.sh",
    "tests/test_outputs.py",
]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def discover_tasks(root: Path) -> list[Path]:
    return sorted(path.parent for path in root.rglob("task.toml"))


def safe_tag(rel: str) -> str:
    value = rel.lower().replace("/", "-").replace("_", "-")
    value = re.sub(r"[^a-z0-9.-]+", "-", value).strip("-")
    return f"tb20-oracle/{value}:local"


def rel_task(root: Path, task: Path) -> str:
    return task.relative_to(root).as_posix()


def selected_tasks(root: Path, selected: list[str]) -> list[Path]:
    if not selected:
        return discover_tasks(root)
    out = []
    for item in selected:
        path = root / item
        if path.is_dir():
            out.append(path)
        else:
            matches = [task for task in discover_tasks(root) if task.name == item or rel_task(root, task) == item]
            out.extend(matches)
    return sorted(set(out))


def run_logged(command: list[str], log_path: Path, cwd: Path | None = None) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        log.write("$ " + " ".join(command) + "\n")
        proc = subprocess.run(command, cwd=str(cwd) if cwd else None, stdout=log, stderr=subprocess.STDOUT, text=True)
    return proc.returncode


def task_problems(task: Path) -> list[str]:
    return [item for item in REQUIRED if not (task / item).is_file()]


def verify_one(root: Path, task: Path, keep_image: bool) -> dict:
    rel = rel_task(root, task)
    logs = task / "oracle-logs"
    if logs.exists():
        shutil.rmtree(logs)
    logs.mkdir(parents=True, exist_ok=True)
    result = {
        "task": rel,
        "startedAt": now(),
        "finishedAt": None,
        "image": safe_tag(rel),
        "buildExitCode": None,
        "solutionExitCode": None,
        "verifierExitCode": None,
        "reward": None,
        "ok": False,
        "logs": {
            "build": str((logs / "build.log").resolve()),
            "solution": str((logs / "solution.log").resolve()),
            "verifier": str((logs / "verifier.log").resolve()),
            "reward": str((logs / "reward.txt").resolve()),
            "result": str((logs / "result.json").resolve()),
        },
    }
    missing = task_problems(task)
    if missing:
        result["problems"] = [f"missing {item}" for item in missing]
        result["finishedAt"] = now()
        write_json(logs / "result.json", result)
        return result

    build_cmd = ["docker", "build", "-t", result["image"], "-f", str(task / "environment/Dockerfile"), str(task / "environment")]
    result["buildExitCode"] = run_logged(build_cmd, logs / "build.log")
    if result["buildExitCode"] != 0:
        (logs / "solution.log").write_text("skipped: docker build failed\n", encoding="utf-8")
        (logs / "verifier.log").write_text("skipped: docker build failed\n", encoding="utf-8")
        (logs / "reward.txt").write_text("0\n", encoding="utf-8")
        result["reward"] = "0"
        result["finishedAt"] = now()
        write_json(logs / "result.json", result)
        return result

    inner = r'''
set +e
mkdir -p /oracle-logs /logs/verifier
/solution/solve.sh > /oracle-logs/solution.log 2>&1
solution_exit=$?
if [ "$solution_exit" -eq 0 ]; then
  /tests/test.sh > /oracle-logs/verifier.log 2>&1
  verifier_exit=$?
else
  echo "skipped: solution failed with exit code $solution_exit" > /oracle-logs/verifier.log
  verifier_exit=99
fi
if [ -f /logs/verifier/reward.txt ]; then
  cp /logs/verifier/reward.txt /oracle-logs/reward.txt
else
  echo 0 > /oracle-logs/reward.txt
fi
if [ ! -s /oracle-logs/solution.log ]; then
  echo "solution completed with no stdout/stderr" > /oracle-logs/solution.log
fi
if [ ! -s /oracle-logs/verifier.log ]; then
  echo "verifier completed with no stdout/stderr" > /oracle-logs/verifier.log
fi
python - <<PY
import json
from pathlib import Path
Path("/oracle-logs/exit-codes.json").write_text(json.dumps({"solution": $solution_exit, "verifier": $verifier_exit}) + "\n")
PY
if [ "$solution_exit" -eq 0 ] && [ "$verifier_exit" -eq 0 ]; then
  exit 0
fi
exit 1
'''
    run_cmd = [
        "docker",
        "run",
        "--rm",
        "-v",
        f"{(task / 'solution').resolve()}:/solution:ro",
        "-v",
        f"{(task / 'tests').resolve()}:/tests:ro",
        "-v",
        f"{logs.resolve()}:/oracle-logs",
        result["image"],
        "bash",
        "-lc",
        inner,
    ]
    result["containerExitCode"] = subprocess.run(run_cmd).returncode
    try:
        codes = json.loads((logs / "exit-codes.json").read_text(encoding="utf-8"))
    except Exception:
        codes = {}
    result["solutionExitCode"] = codes.get("solution")
    result["verifierExitCode"] = codes.get("verifier")
    result["reward"] = (logs / "reward.txt").read_text(encoding="utf-8", errors="replace").strip() if (logs / "reward.txt").exists() else None
    result["ok"] = result["buildExitCode"] == 0 and result["solutionExitCode"] == 0 and result["verifierExitCode"] == 0 and result["reward"] in {"1", "1.0"}
    result["finishedAt"] = now()
    if (logs / "exit-codes.json").exists():
        (logs / "exit-codes.json").unlink()
    write_json(logs / "result.json", result)
    if not keep_image:
        subprocess.run(["docker", "rmi", result["image"]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--task", action="append", default=[])
    parser.add_argument("--evidence", type=Path)
    parser.add_argument("--keep-image", action="store_true")
    args = parser.parse_args()

    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f"root not found: {root}")
    if shutil.which("docker") is None:
        raise SystemExit("docker is required")
    tasks = selected_tasks(root, args.task)
    results = [verify_one(root, task, keep_image=args.keep_image) for task in tasks]
    evidence = {"root": str(root), "taskCount": len(results), "results": results, "ok": bool(results) and all(item["ok"] for item in results)}
    if args.evidence:
        write_json(args.evidence.resolve(), evidence)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if evidence["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
