#!/usr/bin/env python3
"""Batch execute existing TB2.0 tasks with Harbor and package delivery logs."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


SKILL_DIR = Path(__file__).resolve().parents[1]
DEFAULT_HARBOR = SKILL_DIR / ".venv/bin/harbor"
DIFFICULTIES = {"easy", "medium", "hard"}
ROOT_FILES = ["README.md", "README_zh.md"]
SOURCE_FILES = [
    "task.toml",
    "instruction.md",
    "environment/Dockerfile",
    "solution/solve.sh",
    "tests/test.sh",
    "tests/test_outputs.py",
]
LOG_FILES = [
    "agent-logs/run.json",
    "agent-logs/trajectory.json",
    "agent-logs/verifier/ctrf.json",
    "agent-logs/verifier/reward.txt",
]
TASK_ROOT_ALLOWLIST = {"task.toml", "instruction.md", "environment", "solution", "tests", "agent-logs"}


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def write_json(path: Path, data: dict) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def read_state(workspace: Path) -> dict:
    path = workspace / "state.json"
    if not path.exists():
        raise SystemExit(f"workspace not initialized: {workspace}")
    return json.loads(path.read_text(encoding="utf-8"))


def write_state(workspace: Path, state: dict) -> None:
    workspace.mkdir(parents=True, exist_ok=True)
    (workspace / "state.json").write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def record(workspace: Path, stage: str, status: str, evidence: Path | None) -> None:
    if status == "PASS" and (evidence is None or not evidence.exists()):
        raise SystemExit(f"{stage} PASS requires evidence")
    state = read_state(workspace)
    state.setdefault("stages", []).append(
        {
            "stage": stage,
            "status": status,
            "evidence": str(evidence.relative_to(workspace)) if evidence and evidence.is_relative_to(workspace) else str(evidence) if evidence else None,
            "recordedAt": now(),
        }
    )
    write_state(workspace, state)


def latest_status(workspace: Path, stage: str) -> str | None:
    state = read_state(workspace)
    for item in reversed(state.get("stages", [])):
        if item["stage"] == stage:
            return item["status"]
    return None


def require_pass(workspace: Path, stage: str) -> None:
    if latest_status(workspace, stage) != "PASS":
        raise SystemExit(f"{stage} must PASS first")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def discover_tasks(root: Path) -> list[Path]:
    return sorted(path.parent for path in root.rglob("task.toml"))


def selected_tasks(root: Path, items: list[str]) -> list[Path]:
    return [(root / item).resolve() for item in items] if items else discover_tasks(root)


def safe_name(rel: str) -> str:
    return rel.replace("/", "__").replace(" ", "_")


def json_valid(path: Path) -> bool:
    try:
        json.loads(path.read_text(encoding="utf-8"))
        return True
    except Exception:
        return False


def reward_valid(path: Path) -> bool:
    if not path.exists():
        return False
    return path.read_text(encoding="utf-8", errors="replace").strip() in {"0", "1", "0.0", "1.0"}


def inspect_task(root: Path, task_dir: Path, require_logs: bool) -> dict:
    rel = task_dir.relative_to(root).as_posix()
    required = SOURCE_FILES + (LOG_FILES if require_logs else [])
    missing = [item for item in required if not (task_dir / item).is_file()]
    parts = rel.split("/")
    checksum = hashlib.sha256()
    for path in sorted(p for p in task_dir.rglob("*") if p.is_file()):
        item_rel = path.relative_to(task_dir).as_posix()
        checksum.update(item_rel.encode("utf-8"))
        checksum.update(b"\0")
        checksum.update(sha256_file(path).encode("ascii"))
        checksum.update(b"\n")
    log_valid = {
        "runJson": not (task_dir / "agent-logs/run.json").exists() or json_valid(task_dir / "agent-logs/run.json"),
        "trajectoryJson": not (task_dir / "agent-logs/trajectory.json").exists() or json_valid(task_dir / "agent-logs/trajectory.json"),
        "ctrfJson": not (task_dir / "agent-logs/verifier/ctrf.json").exists() or json_valid(task_dir / "agent-logs/verifier/ctrf.json"),
        "rewardTxt": not (task_dir / "agent-logs/verifier/reward.txt").exists() or reward_valid(task_dir / "agent-logs/verifier/reward.txt"),
    }
    return {
        "relativePath": rel,
        "difficultyPathOk": bool(parts and parts[0] in DIFFICULTIES),
        "missing": missing,
        "logsValid": log_valid,
        "contractOk": not missing and all(log_valid.values()),
        "checksum": checksum.hexdigest(),
    }


def root_problems(root: Path, require_logs: bool) -> list[str]:
    problems = []
    for name in ROOT_FILES:
        if not (root / name).is_file():
            problems.append(f"missing root file: {name}")
    if (root / "tasks").exists():
        problems.append("extra tasks/ wrapper is not allowed")
    for task in discover_tasks(root):
        rel = task.relative_to(root).as_posix()
        info = inspect_task(root, task, require_logs=require_logs)
        if not info["difficultyPathOk"]:
            problems.append(f"{rel}: task must live under easy/, medium/, or hard/")
        for item in info["missing"]:
            problems.append(f"{rel}: missing {item}")
        for key, valid in info["logsValid"].items():
            if not valid:
                problems.append(f"{rel}: invalid {key}")
        for child in task.iterdir():
            if child.name in {".DS_Store", "__pycache__"}:
                continue
            if child.name not in TASK_ROOT_ALLOWLIST:
                problems.append(f"{rel}: unexpected task root item {child.name}")
    return problems


def run_capture(command: list[str], timeout: int = 30) -> dict:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
        return {"exitCode": result.returncode, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}
    except Exception as exc:
        return {"exitCode": 999, "stdout": "", "stderr": str(exc)}


def newest_file(root: Path, filename: str) -> Path | None:
    matches = [path for path in root.rglob(filename) if path.is_file()]
    return max(matches, key=lambda path: path.stat().st_mtime) if matches else None


def cmd_init(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    harbor = args.harbor or str(DEFAULT_HARBOR)
    state = {
        "schema": "tb20-batch-execution-delivery.v1",
        "createdAt": now(),
        "sourceRoot": str(args.source_root.resolve()),
        "outputRoot": str(args.output_root.resolve()),
        "harborCli": harbor,
        "stages": [],
    }
    (workspace / "evidence").mkdir(parents=True, exist_ok=True)
    (workspace / "jobs").mkdir(parents=True, exist_ok=True)
    write_state(workspace, state)
    print(json.dumps(state, ensure_ascii=False, indent=2))
    return 0


def cmd_deps(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    harbor = state["harborCli"]
    evidence = {
        "harbor": harbor,
        "harborHelp": run_capture([harbor, "--help"]) if shutil.which(harbor) or Path(harbor).exists() else None,
        "dockerPresent": shutil.which("docker") is not None,
        "dockerInfo": run_capture(["docker", "info", "--format", "{{json .ServerVersion}}"]),
    }
    ok = evidence["harborHelp"] is not None and evidence["harborHelp"]["exitCode"] == 0 and evidence["dockerPresent"] and evidence["dockerInfo"]["exitCode"] == 0
    path = write_json(workspace / "evidence/deps.json", evidence)
    record(workspace, "DEPS", "PASS" if ok else "BLOCKED_BY_DEPENDENCY", path)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if ok else 2


def cmd_inspect(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    root = Path(state["sourceRoot"])
    tasks = [inspect_task(root, task, require_logs=False) for task in selected_tasks(root, args.task) if task.is_dir()]
    problems = root_problems(root, require_logs=False)
    evidence = {"sourceRoot": str(root), "taskCount": len(tasks), "tasks": tasks, "problems": problems}
    path = write_json(workspace / "evidence/inspect.json", evidence)
    ok = bool(tasks) and not problems
    record(workspace, "INSPECT", "PASS" if ok else "FAIL", path if ok else None)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def cmd_run(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    require_pass(workspace, "DEPS")
    require_pass(workspace, "INSPECT")
    state = read_state(workspace)
    root = Path(state["sourceRoot"])
    harbor = state["harborCli"]
    jobs_dir = (args.jobs_dir or workspace / "jobs").resolve()
    jobs_dir.mkdir(parents=True, exist_ok=True)
    results = []
    for task in selected_tasks(root, args.task):
        rel = task.relative_to(root).as_posix()
        name = safe_name(rel)
        command = [harbor, "run", "-p", str(task), "-a", args.agent, "--jobs-dir", str(jobs_dir), "--job-name", name, "--yes"]
        for model in args.model:
            command.extend(["-m", model])
        log_path = workspace / "evidence" / "runs" / f"{name}.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w", encoding="utf-8") as log:
            log.write("$ " + " ".join(command) + "\n")
            started = now()
            proc = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, text=True)
            finished = now()
        results.append({"task": rel, "jobName": name, "exitCode": proc.returncode, "startedAt": started, "finishedAt": finished, "log": str(log_path)})
        if proc.returncode != 0 and args.fail_fast:
            break
    evidence = {"agent": args.agent, "models": args.model, "jobsDir": str(jobs_dir), "results": results}
    path = write_json(workspace / "evidence/run.json", evidence)
    ok = bool(results) and all(item["exitCode"] == 0 for item in results)
    record(workspace, "RUN", "PASS" if ok else "FAIL", path if ok else None)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def copy_artifact(search_root: Path, task_dir: Path, filename: str, dest_rel: str) -> dict:
    src = newest_file(search_root, filename)
    if src is None:
        return {"dest": dest_rel, "copied": False, "reason": "missing source"}
    dest = task_dir / dest_rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    valid = json_valid(dest) if dest.suffix == ".json" else reward_valid(dest) if dest.name == "reward.txt" else True
    return {"source": str(src), "dest": dest_rel, "copied": True, "valid": valid}


def cmd_collect(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    if latest_status(workspace, "RUN") is None:
        raise SystemExit("RUN must be recorded first")
    state = read_state(workspace)
    root = Path(state["sourceRoot"])
    jobs_dir = (args.jobs_dir or workspace / "jobs").resolve()
    results = []
    for task in selected_tasks(root, args.task):
        rel = task.relative_to(root).as_posix()
        job_root = jobs_dir / safe_name(rel)
        search_root = job_root if job_root.exists() else jobs_dir
        artifacts = [
            copy_artifact(search_root, task, "run.json", "agent-logs/run.json"),
            copy_artifact(search_root, task, "trajectory.json", "agent-logs/trajectory.json"),
            copy_artifact(search_root, task, "ctrf.json", "agent-logs/verifier/ctrf.json"),
            copy_artifact(search_root, task, "reward.txt", "agent-logs/verifier/reward.txt"),
        ]
        ok = all(item.get("copied") and item.get("valid", True) for item in artifacts)
        results.append({"task": rel, "ok": ok, "searchRoot": str(search_root), "artifacts": artifacts})
    evidence = {"jobsDir": str(jobs_dir), "results": results}
    path = write_json(workspace / "evidence/collect.json", evidence)
    ok = bool(results) and all(item["ok"] for item in results)
    record(workspace, "COLLECT", "PASS" if ok else "FAIL", path if ok else None)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def copy_delivery(src: Path, dst: Path) -> None:
    def ignore(_dir: str, names: list[str]) -> set[str]:
        return {name for name in names if name in {".DS_Store", "__pycache__"}}

    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst, ignore=ignore)


def cmd_package(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    require_pass(workspace, "COLLECT")
    state = read_state(workspace)
    src = Path(state["sourceRoot"])
    dst = Path(state["outputRoot"])
    problems = root_problems(src, require_logs=True)
    if problems:
        evidence = write_json(workspace / "evidence/package.json", {"problems": problems})
        record(workspace, "PACKAGE", "FAIL", None)
        print(evidence)
        return 1
    copy_delivery(src, dst)
    output_problems = root_problems(dst, require_logs=True)
    tasks = [inspect_task(dst, task, require_logs=True) for task in discover_tasks(dst)]
    evidence = write_json(workspace / "evidence/package.json", {"outputRoot": str(dst), "taskCount": len(tasks), "tasks": tasks, "problems": output_problems})
    ok = not output_problems
    record(workspace, "PACKAGE", "PASS" if ok else "FAIL", evidence if ok else None)
    print(json.dumps({"outputRoot": str(dst), "problems": output_problems}, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def cmd_audit(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    problems = []
    passes = {stage["stage"] for stage in state.get("stages", []) if stage["status"] == "PASS"}
    for stage in ["DEPS", "INSPECT", "RUN", "COLLECT", "PACKAGE"]:
        if stage not in passes:
            problems.append(f"missing PASS stage: {stage}")
    for stage in state.get("stages", []):
        if stage["status"] == "PASS":
            evidence = stage.get("evidence")
            if not evidence or not (workspace / evidence).exists():
                problems.append(f"{stage['stage']} PASS evidence missing")
    evidence = write_json(workspace / "evidence/audit.json", {"problems": problems})
    record(workspace, "AUDIT", "PASS" if not problems else "FAIL", evidence if not problems else None)
    print(json.dumps({"problems": problems}, ensure_ascii=False, indent=2))
    return 0 if not problems else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    init = sub.add_parser("init")
    init.add_argument("--workspace", required=True, type=Path)
    init.add_argument("--source-root", required=True, type=Path)
    init.add_argument("--output-root", required=True, type=Path)
    init.add_argument("--harbor")
    init.set_defaults(func=cmd_init)

    deps = sub.add_parser("deps")
    deps.add_argument("--workspace", required=True, type=Path)
    deps.set_defaults(func=cmd_deps)

    inspect = sub.add_parser("inspect")
    inspect.add_argument("--workspace", required=True, type=Path)
    inspect.add_argument("--task", action="append", default=[])
    inspect.set_defaults(func=cmd_inspect)

    run = sub.add_parser("run")
    run.add_argument("--workspace", required=True, type=Path)
    run.add_argument("--task", action="append", default=[])
    run.add_argument("--agent", default="claude-code")
    run.add_argument("--model", action="append", default=[])
    run.add_argument("--jobs-dir", type=Path)
    run.add_argument("--fail-fast", action="store_true")
    run.set_defaults(func=cmd_run)

    collect = sub.add_parser("collect")
    collect.add_argument("--workspace", required=True, type=Path)
    collect.add_argument("--task", action="append", default=[])
    collect.add_argument("--jobs-dir", type=Path)
    collect.set_defaults(func=cmd_collect)

    package = sub.add_parser("package")
    package.add_argument("--workspace", required=True, type=Path)
    package.set_defaults(func=cmd_package)

    audit = sub.add_parser("audit")
    audit.add_argument("--workspace", required=True, type=Path)
    audit.set_defaults(func=cmd_audit)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
