#!/usr/bin/env python3
"""Strict filesystem gates for Terminal-Bench 2.0 task production."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover
    tomllib = None


REQUIRED_TASK_FILES = [
    "task.toml",
    "instruction.md",
    "environment/Dockerfile",
    "solution/solve.sh",
    "tests/test.sh",
    "tests/test_outputs.py",
]
VALID_STATUSES = {
    "PENDING",
    "RUNNING",
    "PASS",
    "FAIL",
    "BLOCKED",
    "BLOCKED_BY_DEPENDENCY",
    "BLOCKED_BY_REVIEW",
}
DEFAULT_HARBOR_ROOT = Path("/Users/liuyifei/Liu/hub/harbor")
DEFAULT_TB_ROOT = Path("/Users/liuyifei/Liu/hub/terminal-bench-main")


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def read_state(workspace: Path) -> dict:
    path = workspace / "state.json"
    if not path.exists():
        raise SystemExit(f"workspace is not initialized: {workspace}")
    return json.loads(path.read_text(encoding="utf-8"))


def write_state(workspace: Path, state: dict) -> None:
    workspace.mkdir(parents=True, exist_ok=True)
    (workspace / "state.json").write_text(
        json.dumps(state, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def rel_to_workspace(workspace: Path, path: Path | None) -> str | None:
    if path is None:
        return None
    try:
        return str(path.resolve().relative_to(workspace.resolve()))
    except ValueError:
        return str(path.resolve())


def record_stage(
    workspace: Path,
    stage: str,
    status: str,
    evidence: Path | None = None,
    note: str = "",
) -> None:
    if status not in VALID_STATUSES:
        raise SystemExit(f"invalid status: {status}")
    if status == "PASS" and (evidence is None or not evidence.exists()):
        raise SystemExit(f"PASS requires existing evidence for stage {stage}")
    state = read_state(workspace)
    state.setdefault("stages", []).append(
        {
            "stage": stage,
            "status": status,
            "evidence": rel_to_workspace(workspace, evidence),
            "note": note,
            "recordedAt": now(),
        }
    )
    write_state(workspace, state)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_toml(path: Path) -> dict:
    if not path.exists():
        return {}
    if tomllib is not None:
        try:
            with path.open("rb") as handle:
                return tomllib.load(handle)
        except Exception:
            return {}
    return parse_toml_fallback(path.read_text(encoding="utf-8", errors="replace"))


def parse_toml_fallback(text: str) -> dict:
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
            section = root.setdefault(raw.strip("[]").strip(), {})
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


def discover_tasks(root: Path) -> list[Path]:
    tasks = []
    for task_toml in root.rglob("task.toml"):
        task_dir = task_toml.parent
        if any((task_dir / item).exists() for item in REQUIRED_TASK_FILES[1:]):
            tasks.append(task_dir)
    return sorted(tasks)


def inspect_task(source_root: Path, task_dir: Path) -> dict:
    missing = [item for item in REQUIRED_TASK_FILES if not (task_dir / item).is_file()]
    parsed = parse_toml(task_dir / "task.toml")
    metadata = parsed.get("metadata", {}) if isinstance(parsed, dict) else {}
    environment = parsed.get("environment", {}) if isinstance(parsed, dict) else {}
    checksum = hashlib.sha256()
    for path in sorted(p for p in task_dir.rglob("*") if p.is_file()):
        rel = path.relative_to(task_dir).as_posix()
        checksum.update(rel.encode("utf-8"))
        checksum.update(b"\0")
        checksum.update(sha256_file(path).encode("ascii"))
        checksum.update(b"\n")
    return {
        "relativePath": str(task_dir.relative_to(source_root)),
        "standardCompliant": not missing,
        "missingRequiredFiles": missing,
        "difficulty": metadata.get("difficulty"),
        "category": metadata.get("category"),
        "tags": metadata.get("tags", []),
        "dockerImage": environment.get("docker_image"),
        "contentChecksum": checksum.hexdigest(),
    }


def write_json(path: Path, data: dict) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return path


def cmd_init(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    workspace.mkdir(parents=True, exist_ok=True)
    (workspace / "evidence").mkdir(exist_ok=True)
    state = {
        "schema": "tb20-production-flow.v1",
        "createdAt": now(),
        "sourceRoot": str(args.source_root.resolve()),
        "outputRoot": str(args.output_root.resolve()),
        "harborRoot": str(args.harbor_root.resolve()),
        "terminalBenchRoot": str(args.terminal_bench_root.resolve()),
        "stages": [],
    }
    write_state(workspace, state)
    print(json.dumps(state, ensure_ascii=False, indent=2))
    return 0


def command_exists(name: str) -> bool:
    return shutil.which(name) is not None


def run_capture(command: list[str]) -> dict:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=30)
        return {
            "exitCode": result.returncode,
            "stdout": result.stdout.strip(),
            "stderr": result.stderr.strip(),
        }
    except Exception as exc:
        return {"exitCode": 999, "stdout": "", "stderr": str(exc)}


def cmd_deps(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    harbor_root = Path(state["harborRoot"])
    tb_root = Path(state["terminalBenchRoot"])
    docker_info = run_capture(["docker", "info", "--format", "{{json .RegistryConfig.Mirrors}}"])
    evidence = {
        "python": sys.version,
        "dockerCommandPresent": command_exists("docker"),
        "dockerInfo": docker_info,
        "harborReadme": str(harbor_root / "README.md"),
        "harborReadmeExists": (harbor_root / "README.md").is_file(),
        "terminalBenchReadme": str(tb_root / "README.md"),
        "terminalBenchReadmeExists": (tb_root / "README.md").is_file(),
        "harborCliPresent": command_exists("harbor"),
        "terminalBenchCliPresent": command_exists("tb"),
        "uvPresent": command_exists("uv"),
    }
    path = write_json(workspace / "evidence" / "dependencies.json", evidence)
    ok = (
        evidence["dockerCommandPresent"]
        and evidence["dockerInfo"]["exitCode"] == 0
        and evidence["harborReadmeExists"]
        and evidence["terminalBenchReadmeExists"]
    )
    record_stage(workspace, "DEPENDENCIES", "PASS" if ok else "BLOCKED_BY_DEPENDENCY", path)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if ok else 2


def cmd_inspect(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    source_root = Path(state["sourceRoot"])
    task_dirs = [source_root / item for item in args.task] if args.task else discover_tasks(source_root)
    tasks = [inspect_task(source_root, task.resolve()) for task in task_dirs if task.is_dir()]
    evidence = {"sourceRoot": str(source_root), "taskCount": len(tasks), "tasks": tasks}
    path = write_json(workspace / "evidence" / "inspect.json", evidence)
    status = "PASS" if tasks else "FAIL"
    record_stage(workspace, "INSPECT", status, path if tasks else None)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if tasks else 1


def cmd_validate(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    source_root = Path(state["sourceRoot"])
    task_dir = (source_root / args.task).resolve()
    result = inspect_task(source_root, task_dir)
    result["solutionExecutable"] = (task_dir / "solution/solve.sh").exists() and (
        (task_dir / "solution/solve.sh").stat().st_mode & 0o111
    ) != 0
    result["testExecutable"] = (task_dir / "tests/test.sh").exists() and (
        (task_dir / "tests/test.sh").stat().st_mode & 0o111
    ) != 0
    ok = result["standardCompliant"] and result["solutionExecutable"] and result["testExecutable"]
    safe_name = args.task.replace("/", "__")
    path = write_json(workspace / "evidence" / f"validate-{safe_name}.json", result)
    record_stage(workspace, "VALIDATE_TASK", "PASS" if ok else "FAIL", path if ok else None)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def write_manifest(output_root: Path, source_root: Path, tasks: list[dict], copy_tasks: bool) -> Path:
    output_root.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema": "tb20.delivery-manifest.v1",
        "createdAt": now(),
        "sourceRoot": str(source_root),
        "standard": "Terminal-Bench 2.0",
        "summary": {
            "taskCount": len(tasks),
            "compliantTaskCount": sum(1 for item in tasks if item["standardCompliant"]),
        },
        "tasks": tasks,
    }
    path = write_json(output_root / "delivery_manifest.json", manifest)
    lines = ["# Terminal-Bench 2.0 Delivery Index", ""]
    for item in tasks:
        lines.append(f"- `{item['relativePath']}`: {'OK' if item['standardCompliant'] else 'MISSING'}")
    (output_root / "delivery_index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    if copy_tasks:
        task_root = output_root / "tasks"
        if task_root.exists():
            shutil.rmtree(task_root)
        for item in tasks:
            shutil.copytree(source_root / item["relativePath"], task_root / item["relativePath"])
    return path


def cmd_package(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    source_root = Path(state["sourceRoot"])
    output_root = Path(state["outputRoot"])
    task_dirs = [source_root / args.task] if args.task else discover_tasks(source_root)
    tasks = [inspect_task(source_root, task.resolve()) for task in task_dirs if task.is_dir()]
    path = write_manifest(output_root, source_root, tasks, args.copy_tasks)
    ok = bool(tasks) and all(item["standardCompliant"] for item in tasks)
    record_stage(workspace, "DELIVERY_PACKAGE", "PASS" if ok else "FAIL", path if ok else None)
    print(path)
    return 0 if ok else 1


def cmd_record(args: argparse.Namespace) -> int:
    evidence = Path(args.evidence).resolve() if args.evidence else None
    record_stage(args.workspace.resolve(), args.stage, args.status, evidence, args.note)
    return 0


def cmd_audit(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    problems = []
    for item in state.get("stages", []):
        if item["status"] == "PASS":
            evidence = item.get("evidence")
            if not evidence:
                problems.append(f"{item['stage']} PASS lacks evidence")
            else:
                path = workspace / evidence
                if not path.exists():
                    problems.append(f"{item['stage']} evidence missing: {evidence}")
    evidence = {"problems": problems, "stageCount": len(state.get("stages", []))}
    path = write_json(workspace / "evidence" / "audit.json", evidence)
    record_stage(workspace, "FINAL_AUDIT", "PASS" if not problems else "FAIL", path if not problems else None)
    print(json.dumps(evidence, ensure_ascii=False, indent=2))
    return 0 if not problems else 1


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    init = sub.add_parser("init")
    init.add_argument("--workspace", required=True, type=Path)
    init.add_argument("--source-root", required=True, type=Path)
    init.add_argument("--output-root", required=True, type=Path)
    init.add_argument("--harbor-root", type=Path, default=DEFAULT_HARBOR_ROOT)
    init.add_argument("--terminal-bench-root", type=Path, default=DEFAULT_TB_ROOT)
    init.set_defaults(func=cmd_init)

    deps = sub.add_parser("deps")
    deps.add_argument("--workspace", required=True, type=Path)
    deps.set_defaults(func=cmd_deps)

    inspect = sub.add_parser("inspect")
    inspect.add_argument("--workspace", required=True, type=Path)
    inspect.add_argument("--task", action="append", default=[])
    inspect.set_defaults(func=cmd_inspect)

    validate = sub.add_parser("validate-task")
    validate.add_argument("--workspace", required=True, type=Path)
    validate.add_argument("--task", required=True)
    validate.set_defaults(func=cmd_validate)

    package = sub.add_parser("package")
    package.add_argument("--workspace", required=True, type=Path)
    package.add_argument("--task")
    package.add_argument("--copy-tasks", action="store_true")
    package.set_defaults(func=cmd_package)

    record = sub.add_parser("record")
    record.add_argument("--workspace", required=True, type=Path)
    record.add_argument("--stage", required=True)
    record.add_argument("--status", required=True)
    record.add_argument("--evidence")
    record.add_argument("--note", default="")
    record.set_defaults(func=cmd_record)

    audit = sub.add_parser("audit")
    audit.add_argument("--workspace", required=True, type=Path)
    audit.set_defaults(func=cmd_audit)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
