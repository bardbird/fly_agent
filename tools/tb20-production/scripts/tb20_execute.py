#!/usr/bin/env python3
"""Batch execute existing TB2.0 tasks with Harbor and package delivery logs."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path


TOOLKIT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_RUNTIME_VENV = Path(os.environ.get("TB20_RUNTIME_VENV", "/home/ubuntu/tb20-runtime/.venv")).expanduser()
DEFAULT_HARBOR = DEFAULT_RUNTIME_VENV / "bin/harbor"
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
    "agent-logs/claude-code.txt",
    "agent-logs/verifier/ctrf.json",
    "agent-logs/verifier/reward.txt",
]
TASK_ROOT_ALLOWLIST = {"task.toml", "instruction.md", "README.md", ".gitignore", "environment", "solution", "tests", "agent-logs"}
SECRET_ENV_MARKERS = ("KEY", "TOKEN", "SECRET", "PASSWORD")


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


def reward_value(path: Path) -> float | None:
    if not path.exists():
        return None
    try:
        return float(path.read_text(encoding="utf-8", errors="replace").strip())
    except ValueError:
        return None


def redact_env_value(key: str, value: str) -> str:
    if any(marker in key.upper() for marker in SECRET_ENV_MARKERS):
        return f"<redacted len={len(value)}>"
    return value


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
        "claudeCodeTxt": not (task_dir / "agent-logs/claude-code.txt").exists() or (task_dir / "agent-logs/claude-code.txt").is_file(),
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


def newest_trial_root(job_root: Path) -> Path | None:
    candidates = [
        path.parent
        for path in job_root.glob("*/result.json")
        if path.parent.is_dir()
    ]
    return max(candidates, key=lambda path: (path / "result.json").stat().st_mtime) if candidates else None


def load_claude_settings_env(path: Path) -> dict[str, str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise SystemExit(f"Claude settings file not found: {path}")
    except json.JSONDecodeError as exc:
        raise SystemExit(f"Claude settings file is not valid JSON: {path}: {exc}")
    values = data.get("env")
    if not isinstance(values, dict):
        raise SystemExit(f"Claude settings file has no object env field: {path}")
    return {str(key): str(value) for key, value in values.items()}


def parse_env_assignment(item: str) -> tuple[str, str]:
    if "=" not in item:
        raise SystemExit(f"environment assignment must be KEY=VALUE: {item}")
    key, value = item.split("=", 1)
    if not key:
        raise SystemExit(f"environment assignment has empty key: {item}")
    return key, value


def read_job_summary(job_root: Path) -> dict:
    path = job_root / "result.json"
    if not path.exists():
        return {"present": False}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"present": True, "valid": False, "error": str(exc)}
    stats = data.get("stats") or {}
    return {
        "present": True,
        "valid": True,
        "nTotalTrials": data.get("n_total_trials"),
        "nCompletedTrials": stats.get("n_completed_trials"),
        "nErroredTrials": stats.get("n_errored_trials"),
        "evals": stats.get("evals"),
    }


def read_trial_summary(job_root: Path) -> dict:
    trial_root = newest_trial_root(job_root)
    if trial_root is None:
        return {"present": False}
    result_path = trial_root / "result.json"
    try:
        data = json.loads(result_path.read_text(encoding="utf-8"))
    except Exception as exc:
        return {"present": True, "trialRoot": str(trial_root), "valid": False, "error": str(exc)}
    reward = None
    verifier_result = data.get("verifier_result") or {}
    rewards = verifier_result.get("rewards") if isinstance(verifier_result, dict) else None
    if isinstance(rewards, dict):
        raw = rewards.get("reward")
        if isinstance(raw, (int, float)):
            reward = float(raw)
    if reward is None:
        reward = reward_value(trial_root / "verifier/reward.txt")
    exception_info = data.get("exception_info") or {}
    agent = (data.get("config") or {}).get("agent") or {}
    return {
        "present": True,
        "trialRoot": str(trial_root),
        "valid": True,
        "taskName": data.get("task_name"),
        "agent": agent.get("name"),
        "model": agent.get("model_name"),
        "reward": reward,
        "exceptionType": exception_info.get("exception_type"),
        "exceptionMessage": exception_info.get("exception_message"),
        "agentLogPresent": (trial_root / "agent/claude-code.txt").is_file(),
        "trajectoryPresent": (trial_root / "agent/trajectory.json").is_file(),
    }


def cmd_init(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    harbor = args.harbor or str(DEFAULT_HARBOR)
    state = {
        "schema": "tb20-batch-execution-delivery.v1",
        "createdAt": now(),
        "sourceRoot": str(args.source_root.resolve()),
        "outputRoot": str(args.output_root.resolve()),
        "harborCli": harbor,
        "dockerRegistryMirrors": args.docker_registry_mirrors,
        "aptMirror": args.apt_mirror,
        "pythonIndexUrl": args.python_index_url,
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
    tasks = selected_tasks(root, args.task)
    max_workers = max(1, min(args.concurrency, len(tasks) or 1))
    env = os.environ.copy()
    if state.get("dockerRegistryMirrors"):
        env["TB20_DOCKER_REGISTRY_MIRRORS"] = state["dockerRegistryMirrors"]
    if state.get("aptMirror"):
        env["TB20_APT_MIRROR"] = state["aptMirror"]
    if state.get("pythonIndexUrl"):
        env["PIP_INDEX_URL"] = state["pythonIndexUrl"]
        env["UV_INDEX_URL"] = state["pythonIndexUrl"]
    injected_env: dict[str, str] = {}
    if args.claude_settings_from_host:
        settings_env = load_claude_settings_env(args.claude_settings_from_host.expanduser())
        for key, value in settings_env.items():
            env[key] = value
            injected_env[key] = value
    for item in args.agent_env:
        key, value = parse_env_assignment(item)
        env[key] = value
        injected_env[key] = value

    def run_one(task: Path) -> dict:
        rel = task.relative_to(root).as_posix()
        name = safe_name(rel)
        command = [harbor, "run", "-p", str(task), "-a", args.agent, "--jobs-dir", str(jobs_dir), "--job-name", name, "--yes"]
        if args.force_build:
            command.append("--force-build")
        if args.no_delete:
            command.append("--no-delete")
        for model in args.model:
            command.extend(["-m", model])
        log_path = workspace / "evidence" / "runs" / f"{name}.log"
        log_path.parent.mkdir(parents=True, exist_ok=True)
        with log_path.open("w", encoding="utf-8") as log:
            log.write("$ " + " ".join(command) + "\n")
            started = now()
            proc = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, text=True, env=env)
            finished = now()
        job_root = jobs_dir / name
        job_summary = read_job_summary(job_root)
        trial_summary = read_trial_summary(job_root)
        return {
            "task": rel,
            "jobName": name,
            "exitCode": proc.returncode,
            "startedAt": started,
            "finishedAt": finished,
            "log": str(log_path),
            "jobSummary": job_summary,
            "trialSummary": trial_summary,
        }

    results = []
    if args.fail_fast or max_workers == 1:
        for task in tasks:
            item = run_one(task)
            results.append(item)
            if item["exitCode"] != 0 and args.fail_fast:
                break
    else:
        with ThreadPoolExecutor(max_workers=max_workers) as pool:
            futures = {pool.submit(run_one, task): task for task in tasks}
            for future in as_completed(futures):
                results.append(future.result())
    results.sort(key=lambda item: item["task"])
    evidence = {
        "agent": args.agent,
        "models": args.model,
        "jobsDir": str(jobs_dir),
        "concurrency": max_workers,
        "dockerRegistryMirrors": state.get("dockerRegistryMirrors", ""),
        "aptMirror": state.get("aptMirror", ""),
        "pythonIndexUrl": state.get("pythonIndexUrl", ""),
        "injectedAgentEnv": {key: redact_env_value(key, value) for key, value in injected_env.items()},
        "requireNoTrialExceptions": args.require_no_trial_exceptions,
        "requireReward": args.require_reward,
        "results": results,
    }
    path = write_json(workspace / "evidence/run.json", evidence)
    ok = bool(results) and all(item["exitCode"] == 0 for item in results)
    if args.require_no_trial_exceptions:
        ok = ok and all(not (item.get("trialSummary") or {}).get("exceptionType") for item in results)
    if args.require_reward is not None:
        ok = ok and all((item.get("trialSummary") or {}).get("reward") == args.require_reward for item in results)
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


def copy_exact(src: Path, task_dir: Path, dest_rel: str) -> dict:
    if not src.is_file():
        return {"dest": dest_rel, "copied": False, "reason": "missing source"}
    dest = task_dir / dest_rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dest)
    valid = json_valid(dest) if dest.suffix == ".json" else reward_valid(dest) if dest.name == "reward.txt" else True
    return {"source": str(src), "dest": dest_rel, "copied": True, "valid": valid}


def write_minimal_ctrf(trial_root: Path, task_dir: Path) -> dict:
    reward_path = trial_root / "verifier/reward.txt"
    stdout_path = trial_root / "verifier/test-stdout.txt"
    reward = reward_path.read_text(encoding="utf-8", errors="replace").strip() if reward_path.exists() else "0"
    stdout = stdout_path.read_text(encoding="utf-8", errors="replace") if stdout_path.exists() else ""
    passed = reward in {"1", "1.0"}
    dest = task_dir / "agent-logs/verifier/ctrf.json"
    payload = {
        "results": {
            "summary": {
                "tests": 1,
                "passed": 1 if passed else 0,
                "failed": 0 if passed else 1,
                "skipped": 0,
            },
            "tests": [
                {
                    "name": "verifier",
                    "status": "passed" if passed else "failed",
                    "message": stdout[-4000:],
                }
            ],
        }
    }
    write_json(dest, payload)
    return {"source": str(stdout_path) if stdout_path.exists() else str(reward_path), "dest": "agent-logs/verifier/ctrf.json", "copied": True, "valid": json_valid(dest)}


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
        trial_root = newest_trial_root(search_root)
        if trial_root:
            artifacts = [
                copy_exact(trial_root / "result.json", task, "agent-logs/run.json"),
                copy_exact(trial_root / "agent/trajectory.json", task, "agent-logs/trajectory.json"),
                copy_exact(trial_root / "agent/claude-code.txt", task, "agent-logs/claude-code.txt"),
                copy_exact(trial_root / "verifier/reward.txt", task, "agent-logs/verifier/reward.txt"),
            ]
            ctrf = trial_root / "verifier/ctrf.json"
            artifacts.append(copy_exact(ctrf, task, "agent-logs/verifier/ctrf.json") if ctrf.exists() else write_minimal_ctrf(trial_root, task))
        else:
            artifacts = [
                copy_artifact(search_root, task, "run.json", "agent-logs/run.json"),
                copy_artifact(search_root, task, "trajectory.json", "agent-logs/trajectory.json"),
                copy_artifact(search_root, task, "claude-code.txt", "agent-logs/claude-code.txt"),
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
    init.add_argument("--docker-registry-mirrors", default="")
    init.add_argument("--apt-mirror", default="")
    init.add_argument("--python-index-url", default="")
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
    run.add_argument("--concurrency", type=int, default=1)
    run.add_argument("--jobs-dir", type=Path)
    run.add_argument("--fail-fast", action="store_true")
    run.add_argument("--force-build", action="store_true")
    run.add_argument("--no-delete", action="store_true")
    run.add_argument("--agent-env", action="append", default=[], help="Environment variable for the Harbor/agent subprocess in KEY=VALUE format.")
    run.add_argument("--claude-settings-from-host", type=Path, help="Load env values from a host Claude Code settings.json file and inject them into the Harbor subprocess.")
    run.add_argument("--require-no-trial-exceptions", action="store_true", help="Fail RUN if any latest trial result has exception_info.")
    run.add_argument("--require-reward", type=float, help="Fail RUN unless every latest trial has exactly this reward value.")
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
