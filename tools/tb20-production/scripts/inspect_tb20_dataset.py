#!/usr/bin/env python3
"""Inspect and package real Terminal-Bench 2.0 task directories.

The script deliberately reads existing task artifacts only. It does not invent
tasks, rewards, tests, or trajectories.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover - Python < 3.11 fallback
    tomllib = None


REQUIRED_TASK_FILES = [
    "task.toml",
    "instruction.md",
    "environment/Dockerfile",
    "solution/solve.sh",
    "tests/test.sh",
    "tests/test_outputs.py",
]

CLEAN_TASK_FILES = [
    "task.toml",
    "instruction.md",
]

CLEAN_TASK_DIRS = [
    "environment",
    "solution",
    "tests",
    "oracle-logs",
]

CLEAN_AGENT_LOG_FILES = [
    "agent-logs/run.json",
    "agent-logs/trajectory.json",
    "agent-logs/verifier/ctrf.json",
    "agent-logs/verifier/reward.txt",
]

CLEAN_ROOT_FILES = [
    "README.md",
    "README_zh.md",
]

OPTIONAL_LOG_FILES = [
    "agent-logs/run.json",
    "agent-logs/trajectory.json",
    "agent-logs/verifier/ctrf.json",
    "agent-logs/verifier/reward.txt",
]


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def read_json(path: Path):
    try:
        return json.loads(read_text(path))
    except Exception:
        return None


def parse_toml(path: Path) -> dict:
    if not path.is_file():
        return {}
    if tomllib is not None:
        try:
            with path.open("rb") as handle:
                return tomllib.load(handle)
        except Exception:
            return {}
    return parse_task_toml_fallback(read_text(path))


def parse_task_toml_fallback(text: str) -> dict:
    """Parse the small TB 2.0 task.toml subset used by this delivery format."""
    root: dict = {}
    section: dict = root
    lines = text.splitlines()
    index = 0
    while index < len(lines):
        raw = lines[index].strip()
        index += 1
        if not raw or raw.startswith("#"):
            continue
        if raw.startswith("[") and raw.endswith("]"):
            name = raw.strip("[]").strip()
            section = root.setdefault(name, {})
            continue
        if "=" not in raw:
            continue
        key, value = [part.strip() for part in raw.split("=", 1)]
        if value == "[":
            items = []
            while index < len(lines):
                item = lines[index].strip()
                index += 1
                if item == "]":
                    break
                if item.endswith(","):
                    item = item[:-1].strip()
                if item.startswith('"') and item.endswith('"'):
                    item = item[1:-1]
                if item:
                    items.append(item)
            section[key] = items
        else:
            section[key] = parse_toml_scalar(value)
    return root


def parse_toml_scalar(value: str):
    value = value.strip()
    if value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    if value in {"true", "false"}:
        return value == "true"
    try:
        if "." in value:
            return float(value)
        return int(value)
    except ValueError:
        return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def line_count(path: Path) -> int:
    if not path.is_file():
        return 0
    return read_text(path).count("\n") + 1


def file_count(root: Path) -> int:
    if not root.exists():
        return 0
    return sum(1 for path in root.rglob("*") if path.is_file())


def task_name(root: Path, task: Path) -> str:
    try:
        return str(task.relative_to(root))
    except ValueError:
        return task.name


def discover_tasks(root: Path) -> list[Path]:
    tasks = []
    for path in root.rglob("task.toml"):
        task_dir = path.parent
        if any((task_dir / item).is_file() for item in REQUIRED_TASK_FILES[1:]):
            tasks.append(task_dir)
    return sorted(tasks)


def ctrf_summary(path: Path) -> dict:
    data = read_json(path)
    if not isinstance(data, dict):
        return {"tests": 0, "passed": 0, "failed": 0}
    result = data.get("results")
    if isinstance(result, list) and result:
        result = result[0]
    if not isinstance(result, dict):
        result = data
    summary = result.get("summary") if isinstance(result.get("summary"), dict) else {}
    tests = result.get("tests") if isinstance(result.get("tests"), list) else []
    return {
        "tests": int(summary.get("tests", len(tests)) or 0),
        "passed": int(summary.get("passed", sum(1 for t in tests if t.get("status") == "passed")) or 0),
        "failed": int(summary.get("failed", sum(1 for t in tests if t.get("status") == "failed")) or 0),
    }


def inspect_task(root: Path, task: Path) -> dict:
    task_toml = parse_toml(task / "task.toml")
    metadata = task_toml.get("metadata", {}) if isinstance(task_toml, dict) else {}
    environment = task_toml.get("environment", {}) if isinstance(task_toml, dict) else {}

    missing = [item for item in REQUIRED_TASK_FILES if not (task / item).is_file()]
    optional_present = [item for item in OPTIONAL_LOG_FILES if (task / item).is_file()]
    optional_missing = [item for item in OPTIONAL_LOG_FILES if not (task / item).is_file()]

    run = read_json(task / "agent-logs/run.json") or {}
    trajectory = read_json(task / "agent-logs/trajectory.json") or {}
    ctrf = ctrf_summary(task / "agent-logs/verifier/ctrf.json")
    reward_path = task / "agent-logs/verifier/reward.txt"
    reward = read_text(reward_path).strip() if reward_path.is_file() else None

    files = [path for path in task.rglob("*") if path.is_file()]
    checksum = hashlib.sha256()
    for path in sorted(files):
        rel = path.relative_to(task).as_posix()
        checksum.update(rel.encode("utf-8"))
        checksum.update(b"\0")
        checksum.update(sha256_file(path).encode("ascii"))
        checksum.update(b"\n")

    return {
        "taskName": task.name,
        "relativePath": task_name(root, task),
        "absolutePath": str(task.resolve()),
        "standardCompliant": not missing,
        "missingRequiredFiles": missing,
        "optionalLogFilesPresent": optional_present,
        "optionalLogFilesMissing": optional_missing,
        "difficulty": metadata.get("difficulty"),
        "category": metadata.get("category"),
        "tags": metadata.get("tags", []),
        "expertTimeEstimateMin": metadata.get("expert_time_estimate_min"),
        "juniorTimeEstimateMin": metadata.get("junior_time_estimate_min"),
        "dockerImage": environment.get("docker_image"),
        "cpu": environment.get("cpus"),
        "memory": environment.get("memory"),
        "storage": environment.get("storage"),
        "lineCounts": {
            "instruction": line_count(task / "instruction.md"),
            "tests": line_count(task / "tests/test_outputs.py"),
            "solution": line_count(task / "solution/solve.sh"),
        },
        "environmentFileCount": file_count(task / "environment"),
        "testCount": ctrf["tests"],
        "passedTestCount": ctrf["passed"],
        "failedTestCount": ctrf["failed"],
        "reward": reward if reward is not None else run.get("reward"),
        "agentName": (trajectory.get("agent") or {}).get("name") or run.get("agent"),
        "modelName": (trajectory.get("agent") or {}).get("model_name"),
        "trajectorySchema": trajectory.get("schema_version"),
        "trajectorySteps": run.get("trajectory_steps") or len(trajectory.get("steps", [])),
        "agentElapsedSec": run.get("agent_elapsed_sec"),
        "promptTokens": ((run.get("final_metrics") or {}).get("total_prompt_tokens")),
        "completionTokens": ((run.get("final_metrics") or {}).get("total_completion_tokens")),
        "cachedTokens": ((run.get("final_metrics") or {}).get("total_cached_tokens")),
        "contentChecksum": checksum.hexdigest(),
    }


def summarize(tasks: list[dict]) -> dict:
    difficulties: dict[str, int] = {}
    categories: dict[str, int] = {}
    for item in tasks:
        difficulties[item.get("difficulty") or "unknown"] = difficulties.get(item.get("difficulty") or "unknown", 0) + 1
        categories[item.get("category") or "unknown"] = categories.get(item.get("category") or "unknown", 0) + 1
    return {
        "taskCount": len(tasks),
        "compliantTaskCount": sum(1 for item in tasks if item["standardCompliant"]),
        "rewardOneCount": sum(1 for item in tasks if str(item.get("reward")) == "1"),
        "totalTests": sum(int(item.get("testCount") or 0) for item in tasks),
        "totalTrajectorySteps": sum(int(item.get("trajectorySteps") or 0) for item in tasks),
        "totalPromptTokens": sum(int(item.get("promptTokens") or 0) for item in tasks),
        "totalCompletionTokens": sum(int(item.get("completionTokens") or 0) for item in tasks),
        "totalCachedTokens": sum(int(item.get("cachedTokens") or 0) for item in tasks),
        "difficultyDistribution": difficulties,
        "categoryDistribution": categories,
    }


def copy_clean_tree(source: Path, dest: Path) -> None:
    def ignore(_dir: str, names: list[str]) -> set[str]:
        return {name for name in names if name == ".DS_Store" or name == "__pycache__"}

    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(source, dest, ignore=ignore)


def copy_clean_task(source: Path, dest: Path) -> None:
    if dest.exists():
        shutil.rmtree(dest)
    dest.mkdir(parents=True, exist_ok=True)
    for rel in CLEAN_TASK_FILES:
        src = source / rel
        if src.is_file():
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)
    for rel in CLEAN_TASK_DIRS:
        src = source / rel
        if src.is_dir():
            copy_clean_tree(src, dest / rel)
    for rel in CLEAN_AGENT_LOG_FILES:
        src = source / rel
        if src.is_file():
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)


def copy_clean_delivery(output_root: Path, source_root: Path, tasks: list[dict]) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    for stale in ["delivery_manifest.json", "delivery_index.md"]:
        path = output_root / stale
        if path.exists():
            path.unlink()
    stale_tasks = output_root / "tasks"
    if stale_tasks.exists():
        shutil.rmtree(stale_tasks)
    for name in CLEAN_ROOT_FILES:
        src = source_root / name
        if src.is_file():
            shutil.copy2(src, output_root / name)
    for item in tasks:
        copy_clean_task(Path(item["absolutePath"]), output_root / item["relativePath"])


def write_manifest(evidence_root: Path, output_root: Path | None, source_root: Path, tasks: list[dict], copy_tasks: bool) -> dict:
    evidence_root.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema": "fly-agent.tb20.delivery-manifest.v1",
        "createdAt": datetime.now(timezone.utc).isoformat(),
        "sourceRoot": str(source_root.resolve()),
        "outputRoot": str(output_root.resolve()) if output_root else None,
        "copyTasks": copy_tasks,
        "deliveryFormat": "clean",
        "standard": "Terminal-Bench 2.0",
        "requiredExternalDependencies": [
            {"name": "Terminal-Bench 2.0", "role": "task standard and benchmark target"},
            {"name": "Harbor", "role": "external runner/harness for TB 2.0 tasks"},
            {"name": "Docker", "role": "isolated task environment"},
            {"name": "pytest + pytest-json-ctrf", "role": "verifier and CTRF report generation"},
            {"name": "Claude Code / Codex / task skills", "role": "AI-assisted generation and audit of non-fully-automatable stages"},
        ],
        "summary": summarize(tasks),
        "tasks": tasks,
        "nonAutomatableStages": [
            "领域选题价值判断",
            "hard 任务核心算法/协议/系统正确性审计",
            "参考解是否体现合理 expert path",
            "hidden tests 与抗投机测试设计",
            "多模型难度校准结论解释",
            "版权、许可证、敏感信息和交付风险复核",
        ],
    }
    (evidence_root / "delivery_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    rows = [
        "# Terminal-Bench 2.0 Delivery Index",
        "",
        f"- Source: `{source_root.resolve()}`",
        f"- Output: `{output_root.resolve() if output_root else ''}`",
        f"- Tasks: {manifest['summary']['taskCount']}",
        f"- Compliant: {manifest['summary']['compliantTaskCount']}",
        f"- Reward=1: {manifest['summary']['rewardOneCount']}",
        f"- Tests: {manifest['summary']['totalTests']}",
        "",
        "| Task | Difficulty | Category | Tests | Reward | Trajectory | Status |",
        "|---|---|---|---:|---:|---|---|",
    ]
    for item in tasks:
        rows.append(
            f"| `{item['relativePath']}` | {item.get('difficulty') or ''} | "
            f"{item.get('category') or ''} | {item.get('testCount') or 0} | "
            f"{item.get('reward') or ''} | {item.get('trajectorySchema') or ''} | "
            f"{'OK' if item['standardCompliant'] else 'MISSING'} |"
        )
    (evidence_root / "delivery_index.md").write_text("\n".join(rows) + "\n", encoding="utf-8")

    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description="Inspect real Terminal-Bench 2.0 task data.")
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--output-root", type=Path)
    parser.add_argument("--evidence-root", type=Path)
    parser.add_argument("--task", action="append", default=[], help="Relative task path for single/batch selection.")
    parser.add_argument("--copy-tasks", action="store_true")
    args = parser.parse_args()

    source_root = args.source_root.expanduser().resolve()
    if not source_root.exists():
        raise SystemExit(f"source root does not exist: {source_root}")

    if args.task:
        task_dirs = [(source_root / item).resolve() for item in args.task]
    else:
        task_dirs = discover_tasks(source_root)
    tasks = [inspect_task(source_root, task) for task in task_dirs if task.is_dir()]
    result = {
        "sourceRoot": str(source_root),
        "summary": summarize(tasks),
        "tasks": tasks,
    }
    if args.output_root:
        output_root = args.output_root.expanduser().resolve()
        if args.copy_tasks:
            copy_clean_delivery(output_root, source_root, tasks)
        evidence_root = args.evidence_root.expanduser().resolve() if args.evidence_root else output_root.parent / f"{output_root.name}-evidence"
        result["manifest"] = write_manifest(evidence_root, output_root, source_root, tasks, args.copy_tasks)
        result["deliveryRoot"] = str(output_root)
        result["evidenceRoot"] = str(evidence_root)
    elif args.evidence_root:
        evidence_root = args.evidence_root.expanduser().resolve()
        result["manifest"] = write_manifest(evidence_root, None, source_root, tasks, args.copy_tasks)
        result["evidenceRoot"] = str(evidence_root)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
