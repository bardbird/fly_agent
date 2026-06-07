#!/usr/bin/env python3
"""Dataset production checks for Terminal-Bench 2.0 client contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover
    tomllib = None


DIFFICULTIES = {"easy", "medium", "hard"}
DOMAINS = {
    "software-engineering",
    "system-administration",
    "security",
    "data-science",
    "scientific-computing",
    "file-operations",
    "web-network-services",
    "distributed-systems",
    "performance-optimization",
    "algorithms-and-formats",
}
DOMAIN_CHANNELS = {
    "software-engineering": {"github-pr-mining", "software-heritage", "libraries-io"},
    "system-administration": {"debian-source", "linux-man-pages", "systemd-repo", "kubernetes-repo"},
    "security": {"nvd-api", "cve-cvelist", "cwe", "exploit-db", "vulhub"},
    "data-science": {"uci-ml", "openml", "data-gov", "common-crawl-discovery"},
    "scientific-computing": {"netlib", "nist-strd", "suitesparse", "scipy-numpy-tests"},
    "file-operations": {"coreutils", "libarchive", "rsync", "debian-archive-docs", "posix-spec"},
    "web-network-services": {"rfc-editor", "iana-registries", "w3c-whatwg", "curl-tests", "apache-nginx-docs"},
    "distributed-systems": {"cncf-landscape", "kubernetes-repo", "etcd-repo", "prometheus-repo", "jepsen-analyses"},
    "performance-optimization": {"llvm-test-suite", "google-benchmark", "phoronix-test-suite", "open-polybench"},
    "algorithms-and-formats": {"rfc-iana", "netlib", "rosetta-code", "cp-algorithms", "format-spec-repos"},
}
ROOT_FILES = ["README.md", "README_zh.md"]
TASK_FILES = [
    "task.toml",
    "instruction.md",
    "environment/Dockerfile",
    "solution/solve.sh",
    "tests/test.sh",
    "tests/test_outputs.py",
]
TASK_ROOT_ALLOWLIST = {"task.toml", "instruction.md", "environment", "solution", "tests"}
REQUIRED_PRODUCTION_EVIDENCE = [
    "01-intent-analysis.md",
    "02-test-design.md",
    "03-test-review.md",
    "04-implementation-review.md",
    "05-oracle-positive.md",
    "06-negative-controls.md",
    "07-final-review.md",
]
PLACEHOLDER_MARKERS = [
    "TODO",
    "TBD",
    "placeholder",
    "lorem ipsum",
    "example.com",
    "assert False",
]


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
    write_json(workspace / "state.json", state)


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


def read_toml(path: Path) -> dict:
    if not path.exists():
        return {}
    if tomllib is not None:
        try:
            with path.open("rb") as handle:
                return tomllib.load(handle)
        except Exception:
            return {}
    return read_toml_fallback(path.read_text(encoding="utf-8", errors="replace"))


def read_toml_fallback(text: str) -> dict:
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
            section[key] = parse_scalar(value)
    return root


def parse_scalar(value: str):
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


def discover_tasks(root: Path) -> list[Path]:
    return sorted(path.parent for path in root.rglob("task.toml"))


def task_checksum(task_dir: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(p for p in task_dir.rglob("*") if p.is_file()):
        rel = path.relative_to(task_dir).as_posix()
        digest.update(rel.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256_file(path).encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def inspect_task(root: Path, task_dir: Path) -> dict:
    rel = task_dir.relative_to(root).as_posix()
    parts = rel.split("/")
    parsed = read_toml(task_dir / "task.toml")
    metadata = parsed.get("metadata", {}) if isinstance(parsed, dict) else {}
    environment = parsed.get("environment", {}) if isinstance(parsed, dict) else {}
    missing = [item for item in TASK_FILES if not (task_dir / item).is_file()]
    unexpected = []
    for child in task_dir.iterdir():
        if child.name in {".DS_Store", "__pycache__"}:
            continue
        if child.name not in TASK_ROOT_ALLOWLIST:
            unexpected.append(child.name)
    difficulty_dir = parts[0] if parts else None
    return {
        "relativePath": rel,
        "difficultyPathOk": difficulty_dir in DIFFICULTIES,
        "missingFiles": missing,
        "unexpectedTaskRootItems": unexpected,
        "difficulty": metadata.get("difficulty"),
        "difficultyMatchesPath": metadata.get("difficulty") == difficulty_dir,
        "category": metadata.get("category"),
        "tags": metadata.get("tags", []),
        "expertTimeEstimateMin": metadata.get("expert_time_estimate_min"),
        "juniorTimeEstimateMin": metadata.get("junior_time_estimate_min"),
        "dockerImage": environment.get("docker_image"),
        "checksum": task_checksum(task_dir),
    }


def validate_root(root: Path) -> dict:
    tasks = [inspect_task(root, task) for task in discover_tasks(root)]
    problems = []
    for name in ROOT_FILES:
        if not (root / name).is_file():
            problems.append(f"missing root file: {name}")
    if (root / "tasks").exists():
        problems.append("extra tasks/ wrapper is not allowed")
    for task in tasks:
        rel = task["relativePath"]
        if not task["difficultyPathOk"]:
            problems.append(f"{rel}: task must live under easy/, medium/, or hard/")
        for item in task["missingFiles"]:
            problems.append(f"{rel}: missing {item}")
        for item in task["unexpectedTaskRootItems"]:
            problems.append(f"{rel}: unexpected task root item {item}")
        if not task["difficultyMatchesPath"]:
            problems.append(f"{rel}: [metadata].difficulty must match directory")
    return {"root": str(root), "taskCount": len(tasks), "tasks": tasks, "problems": problems}


def slug_from_instruction(path: Path) -> str:
    parent = path.parent.name.strip()
    stem = path.stem.strip()
    value = parent if stem == "instruction" and parent else stem
    value = value.lower().replace("_", "-").replace(" ", "-")
    return "".join(ch for ch in value if ch.isalnum() or ch == "-").strip("-") or "task"


def task_entry(difficulty: str, task_name: str, dataset_root: Path, instruction: Path, packet: Path, evidence_dir: Path) -> dict:
    rel = f"{difficulty}/{task_name}"
    return {
        "difficulty": difficulty,
        "taskName": task_name,
        "relativePath": rel,
        "taskDir": str(dataset_root / rel),
        "instructionSource": str(instruction),
        "productionPacket": str(packet),
        "productionEvidenceDir": str(evidence_dir),
    }


def production_packet(instruction_text: str, difficulty: str, task_name: str, task_dir: Path, evidence_dir: Path) -> str:
    evidence_files = "\n".join(f"- {item}" for item in REQUIRED_PRODUCTION_EVIDENCE)
    return f"""# TB2.0 Task Production Packet

Target task path: {difficulty}/{task_name}
Target difficulty: {difficulty}
Task directory: {task_dir}
Production evidence directory: {evidence_dir}

This packet is a controlled production checklist for the task producer runtime working in the task directory.

Hard gates:
- `instruction.md` is the only task-intent input. Do not invent a different task, domain, artifact, or hidden requirement.
- If the instruction is not sufficient to create a truthful, verifiable TB2.0 task, stop and mark `01-intent-analysis.md` as BLOCKED. Do not fabricate details.
- Tests must be designed and reviewed before verifier implementation. Do not write verifier code first and rationalize it later.
- The final task must be self-contained and runnable by Harbor with Docker.
- The verifier must test the real intended behavior, not file existence, command echoes, or hardcoded implementation details.
- The reference solution must pass the verifier.
- At least one plausible wrong solution or failure mode must fail the verifier.
- Do not create `agent-logs/`; execution delivery creates those from real Harbor runs.

Required final local layout:
task.toml
instruction.md
environment/Dockerfile
solution/solve.sh
tests/test.sh
tests/test_outputs.py

Required evidence files:
{evidence_files}

Evidence content requirements:
- `01-intent-analysis.md`: restate the objective, constraints, underspecified points, accepted minimal assumptions, and whether production is BLOCKED.
- `02-test-design.md`: derive tests from the instruction in natural language: intended behavior, observable outputs, edge cases, ambiguity handling, anti-cheat strategy, and why each test is necessary.
- `03-test-review.md`: independent reviewer critique of the test design: missing behavior, overfitting, false positives, false negatives, hardcoding risks, and required revisions.
- `04-implementation-review.md`: explain Docker environment, fixtures/data, solution strategy, verifier strategy, and dependency choices.
- `05-oracle-positive.md`: include exact commands run for the reference solution/verifier and observed passing output.
- `06-negative-controls.md`: include exact wrong-solution/failure-mode commands or edits and observed failing output.
- `07-final-review.md`: confirm source contract, intent alignment, reviewer concerns resolved, oracle pass, negative control failure, no placeholders, and handoff readiness.

Original instruction.md:
```markdown
{instruction_text}
```
"""


def update_task_state(workspace: Path, entries: list[dict]) -> None:
    state = read_state(workspace)
    existing = {(item["difficulty"], item["taskName"]): item for item in state.get("tasks", [])}
    for entry in entries:
        existing[(entry["difficulty"], entry["taskName"])] = entry
    state["tasks"] = list(existing.values())
    write_state(workspace, state)


def iter_state_tasks(workspace: Path, selected: list[str]) -> list[dict]:
    state = read_state(workspace)
    tasks = state.get("tasks", [])
    if not selected:
        return tasks
    selected_set = set(selected)
    return [item for item in tasks if item["relativePath"] in selected_set or item["taskName"] in selected_set]


def file_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def source_quality_problems(root: Path) -> list[str]:
    problems = []
    for task_dir in discover_tasks(root):
        rel = task_dir.relative_to(root).as_posix()
        for item in TASK_FILES:
            path = task_dir / item
            if path.is_file() and not path.read_text(encoding="utf-8", errors="replace").strip():
                problems.append(f"{rel}: {item} is empty")
        combined = "\n".join(file_text(task_dir / item) for item in TASK_FILES if (task_dir / item).is_file())
        lowered = combined.lower()
        for marker in PLACEHOLDER_MARKERS:
            if marker.lower() in lowered:
                problems.append(f"{rel}: placeholder marker found: {marker}")
        tests_py = task_dir / "tests/test_outputs.py"
        if tests_py.is_file() and "def test_" not in file_text(tests_py):
            problems.append(f"{rel}: tests/test_outputs.py has no pytest test function")
        test_sh = task_dir / "tests/test.sh"
        if test_sh.is_file():
            text = file_text(test_sh)
            if "/logs/verifier/reward.txt" not in text:
                problems.append(f"{rel}: tests/test.sh must write /logs/verifier/reward.txt")
        solution = task_dir / "solution/solve.sh"
        if solution.is_file() and not file_text(solution).startswith("#!"):
            problems.append(f"{rel}: solution/solve.sh must start with a shebang")
    return problems


def registered_task_problems(workspace: Path, selected: list[str]) -> list[str]:
    problems = []
    for item in iter_state_tasks(workspace, selected):
        rel = item["relativePath"]
        task_dir = Path(item["taskDir"])
        if not task_dir.is_dir():
            problems.append(f"{rel}: registered task directory does not exist")
            continue
        for required in TASK_FILES:
            if not (task_dir / required).is_file():
                problems.append(f"{rel}: missing {required}")
    return problems


def cmd_init(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = {
        "schema": "tb20-dataset-production.v2",
        "createdAt": now(),
        "datasetRoot": str(args.dataset_root.resolve()),
        "stages": [],
        "tasks": [],
    }
    (workspace / "evidence").mkdir(parents=True, exist_ok=True)
    (workspace / "production-packets").mkdir(parents=True, exist_ok=True)
    args.dataset_root.resolve().mkdir(parents=True, exist_ok=True)
    write_state(workspace, state)
    print(json.dumps(state, ensure_ascii=False, indent=2))
    return 0


def cmd_ingest_instruction(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    state = read_state(workspace)
    dataset_root = Path(state["datasetRoot"])
    if args.difficulty not in DIFFICULTIES:
        raise SystemExit(f"invalid difficulty: {args.difficulty}")
    if args.task_name and len(args.instruction) != 1:
        raise SystemExit("--task-name is only valid with exactly one --instruction")
    write_text_if_missing(dataset_root / "README.md", "# Terminal-Bench 2.0 Dataset\n")
    write_text_if_missing(dataset_root / "README_zh.md", "# Terminal-Bench 2.0 数据集\n")
    entries = []
    for instruction in args.instruction:
        src = instruction.resolve()
        if not src.is_file():
            raise SystemExit(f"instruction.md not found: {src}")
        task_name = args.task_name or slug_from_instruction(src)
        task_dir = dataset_root / args.difficulty / task_name
        task_dir.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, task_dir / "instruction.md")
        evidence_dir = workspace / "evidence" / "task-production" / f"{args.difficulty}__{task_name}"
        evidence_dir.mkdir(parents=True, exist_ok=True)
        packet_path = workspace / "production-packets" / f"{args.difficulty}__{task_name}.md"
        packet_path.parent.mkdir(parents=True, exist_ok=True)
        packet_path.write_text(production_packet(file_text(src), args.difficulty, task_name, task_dir, evidence_dir), encoding="utf-8")
        entries.append(task_entry(args.difficulty, task_name, dataset_root, src, packet_path, evidence_dir))
    update_task_state(workspace, entries)
    evidence = write_json(workspace / "evidence/ingest-instruction.json", {"tasks": entries})
    record(workspace, "INGEST_INSTRUCTION", "PASS", evidence)
    print(json.dumps({"tasks": entries}, ensure_ascii=False, indent=2))
    return 0


def production_evidence_problems(workspace: Path, selected: list[str]) -> list[str]:
    problems = []
    tasks = iter_state_tasks(workspace, selected)
    selected_paths = {item["relativePath"] for item in tasks}
    for item in tasks:
        rel = item["relativePath"]
        evidence_dir = Path(item["productionEvidenceDir"])
        for name in REQUIRED_PRODUCTION_EVIDENCE:
            path = evidence_dir / name
            if not path.is_file():
                problems.append(f"{rel}: missing production evidence {name}")
                continue
            if path.suffix == ".json":
                continue
            text = file_text(path).strip()
            if len(text) < 120:
                problems.append(f"{rel}: production evidence {name} is too thin")
            lowered = text.lower()
            if "todo" in lowered or "placeholder" in lowered:
                problems.append(f"{rel}: production evidence {name} contains placeholder text")
            if name == "01-intent-analysis.md" and "blocked" in lowered:
                problems.append(f"{rel}: intent analysis is BLOCKED")
        if rel not in selected_paths:
            problems.append(f"{rel}: task selection mismatch")
    return problems


def cmd_source_audit(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    require_pass(workspace, "INGEST_INSTRUCTION")
    state = read_state(workspace)
    root = Path(state["datasetRoot"])
    result = validate_root(root)
    registered = registered_task_problems(workspace, args.task)
    quality = source_quality_problems(root)
    production = production_evidence_problems(workspace, args.task)
    result["registeredTaskProblems"] = registered
    result["qualityProblems"] = quality
    result["productionEvidenceProblems"] = production
    result["problems"].extend(registered)
    result["problems"].extend(quality)
    result["problems"].extend(production)
    evidence = write_json(workspace / "evidence/source-audit.json", result)
    ok = not result["problems"] and result["taskCount"] > 0
    record(workspace, "SOURCE_AUDIT", "PASS" if ok else "FAIL", evidence if ok else None)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if ok else 1


def write_text_if_missing(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        path.write_text(text, encoding="utf-8")


def parse_json_arg(text: str | None) -> dict:
    if not text:
        return {}
    try:
        data = json.loads(text)
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError as exc:
        raise SystemExit(f"invalid JSON: {exc}") from exc


def project_root() -> Path:
    return Path(__file__).resolve().parents[3]


def codex_home() -> Path:
    return Path(os.environ.get("CODEX_HOME") or Path.home() / ".codex").expanduser().resolve()


def skill_dir() -> Path:
    return Path(__file__).resolve().parents[1]


def ensure_codex_skill_installed(config: dict) -> dict:
    name = "tb20-dataset-production"
    source = skill_dir()
    dest = codex_home() / "skills" / name
    mode = str(config.get("codexSkillSyncMode", "") or "symlink").strip()
    evidence = {
        "skillName": name,
        "source": str(source),
        "dest": str(dest),
        "mode": mode,
        "status": "PENDING",
    }
    if not (source / "SKILL.md").is_file():
        evidence["status"] = "BLOCKED"
        evidence["reason"] = "source SKILL.md missing"
        return evidence
    dest.parent.mkdir(parents=True, exist_ok=True)
    if mode == "off":
        if (dest / "SKILL.md").is_file():
            evidence["status"] = "PASS"
            evidence["reason"] = "existing installed skill"
        else:
            evidence["status"] = "BLOCKED"
            evidence["reason"] = "skill sync disabled and installed skill missing"
        return evidence
    if dest.is_symlink():
        current = dest.resolve()
        if current != source.resolve():
            dest.unlink()
        else:
            evidence["status"] = "PASS"
            evidence["reason"] = "existing symlink"
            return evidence
    if dest.exists() and not dest.is_symlink():
        marker = dest / ".tb20-managed-by-fly-agent"
        if not marker.exists():
            evidence["status"] = "BLOCKED"
            evidence["reason"] = "destination exists and is not managed by fly-agent"
            return evidence
        shutil.rmtree(dest)
    try:
        if mode == "copy":
            shutil.copytree(source, dest, ignore=shutil.ignore_patterns(".venv", "__pycache__", ".DS_Store"))
            (dest / ".tb20-managed-by-fly-agent").write_text(str(source) + "\n", encoding="utf-8")
            evidence["status"] = "PASS"
            evidence["reason"] = "copied"
        else:
            dest.symlink_to(source, target_is_directory=True)
            evidence["status"] = "PASS"
            evidence["reason"] = "symlinked"
    except Exception as exc:
        evidence["status"] = "BLOCKED"
        evidence["reason"] = str(exc)
    return evidence


def codex_request_text(contract_path: Path, output: Path) -> str:
    return f"""Use the $tb20-dataset-production skill.

Execute the TB2.0 dataset production stage described by this structured contract:
{contract_path}

Hard requirements:
- Trigger and follow the installed `tb20-dataset-production` Codex skill.
- Use the contract as the only production input.
- Write all required files directly under:
  {output}
- Required files are exactly:
  source.json
  license.txt
  acquisition.log
  materials.md
  problem-card.md
  instruction.md
  test-generation-brief.md
- Do not create a Terminal-Bench task directory here.
- If the source evidence is insufficient, write BLOCKED evidence files and explain the blocker.
- Do not claim success in the final response unless all required files are written.
"""


def run_codex_adapter(output: Path, workspace: Path, source: dict, brief_text: str, config: dict) -> dict:
    install = ensure_codex_skill_installed(config)
    write_json(workspace / "codex-skill-install.json", install)
    if install["status"] != "PASS":
        return {
            "install": install,
            "command": [],
            "request": "",
            "contract": "",
            "finalMessage": "",
            "exitCode": 2,
            "stdout": "",
            "stderr": install.get("reason", "Codex skill install failed"),
        }
    contract = {
        "source": source,
        "brief": brief_text,
        "required_outputs": [
            "source.json",
            "license.txt",
            "acquisition.log",
            "materials.md",
            "problem-card.md",
            "instruction.md",
            "test-generation-brief.md",
        ],
        "instruction_sections": [
            "Context",
            "Files Available",
            "Task",
            "Input Format",
            "Required Output",
            "Behavioral Requirements",
            "Edge Cases",
            "Constraints",
            "Examples",
            "Success Criteria",
        ],
    }
    contract_path = write_json(workspace / "codex-contract.json", contract)
    request_path = workspace / "codex-request.md"
    request_path.write_text(codex_request_text(contract_path, output), encoding="utf-8")
    final_path = workspace / "codex-final-message.md"
    codex = str(config.get("codexBinary", "") or "codex")
    command = [
        codex,
        "exec",
        "--skip-git-repo-check",
        "-C",
        str(project_root()),
        "--add-dir",
        str(output),
        "--sandbox",
        str(config.get("codexSandbox", "") or "danger-full-access"),
        "--ask-for-approval",
        "never",
        "--output-last-message",
        str(final_path),
    ]
    model = str(config.get("codexModel", "") or "").strip()
    if model:
        command.extend(["--model", model])
    profile = str(config.get("codexProfile", "") or "").strip()
    if profile:
        command.extend(["--profile", profile])
    command.append(request_path.read_text(encoding="utf-8"))
    env = {
        **os.environ,
        "TB20_OUTPUT_ROOT": str(output),
        "TB20_WORKSPACE": str(workspace),
        "TB20_ADAPTER_CONTRACT": str(contract_path),
    }
    try:
        proc = subprocess.run(command, cwd=str(project_root()), text=True, capture_output=True, env=env)
        exit_code = proc.returncode
        stdout = proc.stdout
        stderr = proc.stderr
    except Exception as exc:
        exit_code = 127
        stdout = ""
        stderr = str(exc)
    log = {
        "install": install,
        "command": command[:-1] + ["<codex-request.md>"],
        "request": str(request_path),
        "contract": str(contract_path),
        "finalMessage": str(final_path),
        "exitCode": exit_code,
        "stdout": stdout,
        "stderr": stderr,
    }
    write_json(workspace / "codex-run.json", log)
    return log


def output_quality_problems(output: Path) -> list[str]:
    problems = []
    required = ["source.json", "license.txt", "acquisition.log", "materials.md", "problem-card.md", "instruction.md", "test-generation-brief.md"]
    for name in required:
        path = output / name
        if not path.is_file():
            problems.append(f"missing output: {name}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace").strip()
        if len(text) < 20:
            problems.append(f"{name} is too thin")
        lowered = text.lower()
        for marker in ["todo", "tbd", "placeholder", "lorem ipsum"]:
            if marker in lowered:
                problems.append(f"{name} contains placeholder marker: {marker}")
        if "blocked" in lowered and name in {"problem-card.md", "instruction.md", "test-generation-brief.md"}:
            problems.append(f"{name} is BLOCKED")
    instruction = file_text(output / "instruction.md")
    for heading in [
        "## Context",
        "## Files Available",
        "## Task",
        "## Required Output",
        "## Behavioral Requirements",
        "## Success Criteria",
    ]:
        if heading not in instruction:
            problems.append(f"instruction.md missing section: {heading}")
    test_brief = file_text(output / "test-generation-brief.md")
    for item in ["Observable outputs", "Fixture plan", "Boundary tests", "Wrong implementations to reject"]:
        if item not in test_brief:
            problems.append(f"test-generation-brief.md missing item: {item}")
    return problems


def cmd_prepare_instruction(args: argparse.Namespace) -> int:
    domain = args.domain.strip()
    channel = args.source_channel.strip()
    if domain not in DOMAINS:
        raise SystemExit(f"invalid domain: {domain}")
    if channel not in DOMAIN_CHANNELS[domain]:
        raise SystemExit(f"invalid source channel for {domain}: {channel}")
    workspace = args.workspace.resolve()
    output = args.output_root.resolve()
    config = parse_json_arg(args.channel_config)
    workspace.mkdir(parents=True, exist_ok=True)
    output.mkdir(parents=True, exist_ok=True)
    brief_text = args.brief or ""
    if args.brief_file:
        brief_text = args.brief_file.read_text(encoding="utf-8", errors="replace")
    source = {
        "domain": domain,
        "source_channel": channel,
        "acquisition_method": config.get("acquisitionMethod", ""),
        "source_name": config.get("sourceName", ""),
        "source_url": config.get("sourceUrl", ""),
        "license": config.get("license", ""),
        "license_url": config.get("licenseUrl", ""),
        "terms_url": config.get("termsUrl", ""),
        "github_token_source": "reuse-swe-pro-token-pool" if channel.startswith("github") else "",
        "redistribution_risk": config.get("redistributionRisk", "medium"),
        "allowed_for_task_generation": bool(config.get("allowedForTaskGeneration", False)),
        "created_at": now(),
    }
    gate_problems = []
    for key in ["source_name", "source_url", "license", "terms_url"]:
        if not str(source.get(key) or "").strip():
            gate_problems.append(f"missing required source metadata: {key}")
    if not source["allowed_for_task_generation"]:
        gate_problems.append("allowed_for_task_generation must be true before production")
    write_json(output / "source.json", source)
    (output / "license.txt").write_text(
        f"license={source['license']}\nlicense_url={source['license_url']}\nterms_url={source['terms_url']}\n",
        encoding="utf-8",
    )
    adapter_type = str(config.get("adapterType", "") or "codex").strip() or "codex"
    adapter_result = None
    if adapter_type == "codex" and not gate_problems:
        adapter_result = run_codex_adapter(output, workspace, source, brief_text, config)
        if adapter_result["exitCode"] != 0:
            gate_problems.append("codex adapter command failed")
    elif adapter_type != "codex":
        gate_problems.append(f"unsupported adapterType: {adapter_type}")
    else:
        (output / "acquisition.log").write_text(
            "Structured acquisition workspace created. No executable source adapter completed this run.\n",
            encoding="utf-8",
        )
        (output / "materials.md").write_text(
            f"# Materials\n\nDomain: {domain}\nSource channel: {channel}\n\n## Brief\n\n{brief_text}\n\n"
            "## Controlled Status\n\nBLOCKED: source-backed extracted rules and artifacts were not produced by an adapter.\n",
            encoding="utf-8",
        )
        (output / "problem-card.md").write_text(
            "# Problem Card\n\n"
            f"Domain: {domain}\nSource channel: {channel}\n\n"
            "## Real Problem\n\nBLOCKED: source-backed problem mining not completed by adapter.\n\n"
            "## Expected Behavior\n\nBLOCKED\n\n## Verifiability\n\nBLOCKED\n",
            encoding="utf-8",
        )
        (output / "instruction.md").write_text(
            "# BLOCKED\n\n"
            "This instruction is not production-ready. Source-backed material acquisition and problem mining must complete first.\n",
            encoding="utf-8",
        )
        (output / "test-generation-brief.md").write_text(
            "# Test Generation Brief\n\nBLOCKED: instruction.md is not production-ready.\n",
            encoding="utf-8",
        )
    quality_problems = output_quality_problems(output)
    problems = gate_problems + quality_problems
    status = "PASS" if not problems else "BLOCKED"
    result = {
        "workspace": str(workspace),
        "outputRoot": str(output),
        "source": source,
        "adapter": adapter_result,
        "status": status,
        "problems": problems,
    }
    write_json(workspace / "prepare-instruction-gate.json", result)
    write_json(workspace / "prepare-instruction.json", result)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if status == "PASS" else 2


def cmd_inspect(args: argparse.Namespace) -> int:
    result = validate_root(args.root.resolve())
    result.pop("problems", None)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["taskCount"] else 1


def cmd_validate(args: argparse.Namespace) -> int:
    result = validate_root(args.root.resolve())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if not result["problems"] and result["taskCount"] else 1


def copy_clean(src: Path, dst: Path) -> None:
    def ignore(_dir: str, names: list[str]) -> set[str]:
        return {name for name in names if name in {".DS_Store", "__pycache__", "agent-logs"}}

    if dst.exists():
        shutil.rmtree(dst)
    shutil.copytree(src, dst, ignore=ignore)


def cmd_package(args: argparse.Namespace) -> int:
    workspace = args.workspace.resolve()
    require_pass(workspace, "SOURCE_AUDIT")
    root = Path(read_state(workspace)["datasetRoot"])
    result = validate_root(root)
    if result["problems"]:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    copy_clean(root, args.output.resolve())
    print(args.output.resolve())
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    init = sub.add_parser("init")
    init.add_argument("--workspace", required=True, type=Path)
    init.add_argument("--dataset-root", required=True, type=Path)
    init.set_defaults(func=cmd_init)

    ingest = sub.add_parser("ingest-instruction")
    ingest.add_argument("--workspace", required=True, type=Path)
    ingest.add_argument("--difficulty", required=True)
    ingest.add_argument("--instruction", action="append", required=True, type=Path)
    ingest.add_argument("--task-name")
    ingest.set_defaults(func=cmd_ingest_instruction)

    source_audit = sub.add_parser("source-audit")
    source_audit.add_argument("--workspace", required=True, type=Path)
    source_audit.add_argument("--task", action="append", default=[])
    source_audit.set_defaults(func=cmd_source_audit)

    inspect = sub.add_parser("inspect")
    inspect.add_argument("--root", required=True, type=Path)
    inspect.set_defaults(func=cmd_inspect)

    validate = sub.add_parser("validate")
    validate.add_argument("--root", required=True, type=Path)
    validate.set_defaults(func=cmd_validate)

    package = sub.add_parser("package")
    package.add_argument("--workspace", required=True, type=Path)
    package.add_argument("--output", required=True, type=Path)
    package.set_defaults(func=cmd_package)

    prepare = sub.add_parser("prepare-instruction")
    prepare.add_argument("--workspace", required=True, type=Path)
    prepare.add_argument("--output-root", required=True, type=Path)
    prepare.add_argument("--domain", required=True)
    prepare.add_argument("--source-channel", required=True)
    prepare.add_argument("--brief", default="")
    prepare.add_argument("--brief-file", type=Path)
    prepare.add_argument("--channel-config", default="{}")
    prepare.set_defaults(func=cmd_prepare_instruction)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
