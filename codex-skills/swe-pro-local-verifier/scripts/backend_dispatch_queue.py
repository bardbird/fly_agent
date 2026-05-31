#!/usr/bin/env python3
from __future__ import annotations

import argparse
import csv
import datetime as dt
import fcntl
import hashlib
import json
import os
import shlex
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[3]
SKILL_ROOT = ROOT / "codex-skills" / "swe-pro-local-verifier"
PREPARE_SCRIPT = ROOT / "tools" / "swe-pro-production" / "scripts" / "prepare_tasks_from_candidates.py"
HANDOFF_SCRIPT = SKILL_ROOT / "scripts" / "handoff_resume.py"
QC_SCRIPT = SKILL_ROOT / "scripts" / "qc_review_evidence.py"
DEFAULT_BASE_URL = "http://127.0.0.1:8080"
DEFAULT_STATE_FILE = ".swe-ai/verifier_dispatch_queue.json"
DEFAULT_PACKAGE_ROOT = os.environ.get("SWE_VERIFIER_PACKAGE_ROOT", "/data/fly-agent/swe-output")
DEFAULT_HANDOFF_ROOT = os.environ.get("SWE_BACKEND_VISIBLE_PACKAGE_ROOT", "/data/fly-agent/swe-output")
TASK_SPEC_FILES = [
    "task.json",
    "problem_statement.md",
    "patches/gold.patch",
    "patches/test.patch",
    "scripts/run_selected_tests.sh",
    "scripts/verify_patch_application.sh",
    "dockerfiles/Dockerfile",
    "runtime_env.json",
]

TASK_TERMINAL_STATUSES = {"COMPLETED", "DELIVERED"}
TASK_ACTIVE_STATUSES = {"RUNNING"}
READY_VALIDATION_STATUSES = {"pending_verifier", "claimed_verifier", "local_verified", "handoff_failed"}
DISCOVER_BUSY_STATUSES = {"creating_task", "preparing_package", "pending_verifier", "claimed_verifier", "local_verified", "handed_off"}

CANDIDATE_CSV_FIELDS = [
    "candidate_id",
    "repo",
    "pr_url",
    "issue_url",
    "issue_numbers",
    "problem_statement",
    "hints_text",
    "base_commit",
    "fix_commit",
    "merge_commit",
    "merged_at",
    "updated_at",
    "primary_language",
    "secondary_languages",
    "patch_files",
    "source_files",
    "insertions",
    "deletions",
    "total_changed",
    "gold_patch_files",
    "gold_source_files",
    "gold_insertions",
    "gold_deletions",
    "gold_total_changed",
    "test_patch_files",
    "test_insertions",
    "test_deletions",
    "test_total_changed",
    "test_patch_present",
    "fail_to_pass",
    "pass_to_pass",
    "benchmark_status",
    "failed_history_status",
    "generated_or_i18n_ratio",
    "score",
    "candidate_grade",
    "grade_reason",
    "candidate_status",
    "duplicate_status",
    "notes",
]


def request_json(base_url: str, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code} {method} {path}: {body}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"{method} {path} failed: {exc}") from exc
    result = json.loads(body)
    if isinstance(result, dict) and result.get("code") not in (None, 0, 200, "SUCCESS"):
        raise RuntimeError(f"{method} {path} failed: {result}")
    return result.get("data") if isinstance(result, dict) and "data" in result else result


def get_json(base_url: str, path: str, params: dict[str, Any]) -> dict[str, Any]:
    clean = {key: value for key, value in params.items() if value is not None and value != ""}
    query = urllib.parse.urlencode(clean)
    result = request_json(base_url, "GET", path + ("?" + query if query else ""))
    if not isinstance(result, dict):
        raise RuntimeError(f"expected object response from {path}")
    return result


def post_json(base_url: str, path: str, payload: dict[str, Any]) -> dict[str, Any]:
    result = request_json(base_url, "POST", path, payload)
    if not isinstance(result, dict):
        raise RuntimeError(f"expected object response from {path}")
    return result


def today_text() -> str:
    return dt.date.today().isoformat()


def now_utc() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def parse_languages(value: str) -> list[str]:
    if not value or value.lower() == "all":
        return []
    result: list[str] = []
    seen: set[str] = set()
    for raw in value.split(","):
        language = raw.strip().lower()
        if language and language not in seen:
            result.append(language)
            seen.add(language)
    return result


def parse_iso_datetime(value: Any) -> dt.datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def is_stale(item: dict[str, Any], stale_minutes: int) -> bool:
    if stale_minutes <= 0:
        return False
    updated = parse_iso_datetime(item.get("updated_at") or item.get("created_at"))
    if updated is None:
        return True
    age = dt.datetime.now(dt.timezone.utc) - updated
    return age.total_seconds() >= stale_minutes * 60


def positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be > 0")
    return parsed


def non_negative_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be >= 0")
    return parsed


def candidate_key(candidate: dict[str, Any]) -> str:
    candidate_id = candidate.get("id")
    if candidate_id is not None:
        return f"candidate:{candidate_id}"
    pr_url = candidate.get("prUrl")
    if pr_url:
        return f"pr:{pr_url}"
    return f"repo-pr:{candidate.get('repo')}#{candidate.get('number')}"


def task_name(candidate: dict[str, Any]) -> str:
    repo = str(candidate.get("repo") or "repo").replace("/", "-")
    number = candidate.get("number") or "unknown"
    return f"production-task-{repo}-{number}"


def package_name(candidate: dict[str, Any]) -> str:
    repo = str(candidate.get("repo") or "repo").split("/")[-1].lower().replace("_", "-")
    number = candidate.get("number") or "unknown"
    return f"production-task-{repo}-{number}"


def repo_name(repo: dict[str, Any]) -> str:
    return str(repo.get("repo") or "").strip()


def repo_language(repo: dict[str, Any]) -> str:
    return str(repo.get("primaryLanguage") or "unknown").strip().lower() or "unknown"


def load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {"version": 2, "items": {}}
    try:
        state = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        backup = path.with_suffix(path.suffix + f".corrupt-{int(time.time())}")
        path.rename(backup)
        return {"version": 2, "items": {}, "corrupt_backup": str(backup)}
    if not isinstance(state, dict):
        return {"version": 2, "items": {}}
    state.setdefault("version", 2)
    state.setdefault("items", {})
    return state


def save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    tmp.replace(path)


def with_state_lock(path: Path, fn: Callable[[dict[str, Any]], Any]) -> Any:
    path.parent.mkdir(parents=True, exist_ok=True)
    lock_path = path.with_suffix(path.suffix + ".lock")
    with lock_path.open("w", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file, fcntl.LOCK_EX)
        state = load_state(path)
        result = fn(state)
        save_state(path, state)
        return result


def update_item(path: Path, key: str, **fields: Any) -> None:
    def edit(state: dict[str, Any]) -> None:
        item = state.setdefault("items", {}).setdefault(key, {})
        item.update(fields)
        item["updated_at"] = now_utc()

    with_state_lock(path, edit)


def reserve_candidate_for_discover(
    path: Path,
    key: str,
    *,
    token: str,
    language: str,
    repo: str,
    candidate: dict[str, Any],
    owner: str,
    stale_minutes: int,
    overwrite: bool,
) -> bool:
    def edit(state: dict[str, Any]) -> bool:
        items = state.setdefault("items", {})
        existing = items.get(key)
        if existing and not overwrite:
            status = existing.get("status")
            if status in DISCOVER_BUSY_STATUSES and not is_stale(existing, stale_minutes):
                return False
            if status not in {"failed", "skipped", "handoff_failed"} and not is_stale(existing, stale_minutes):
                return False
        previous = dict(existing) if isinstance(existing, dict) else None
        items[key] = {
            "status": "creating_task",
            "discover_token": token,
            "discover_owner": owner,
            "language": language,
            "repo": repo,
            "candidate_id": candidate.get("id"),
            "pr_url": candidate.get("prUrl"),
            "pr_number": candidate.get("number"),
            "previous_status": previous.get("status") if previous else None,
            "reserved_at": now_utc(),
            "updated_at": now_utc(),
        }
        return True

    return bool(with_state_lock(path, edit))


def update_reserved_item(path: Path, key: str, token: str, **fields: Any) -> bool:
    def edit(state: dict[str, Any]) -> bool:
        item = state.setdefault("items", {}).get(key)
        if not isinstance(item, dict) or item.get("discover_token") != token:
            return False
        item.update(fields)
        item["updated_at"] = now_utc()
        return True

    return bool(with_state_lock(path, edit))


def list_allowed_repos(args: argparse.Namespace, language: str | None) -> list[dict[str, Any]]:
    repos: list[dict[str, Any]] = []
    page = 1
    while True:
        response = get_json(
            args.base_url,
            "/api/v1/swe/sca-report/allowed-repos",
            {
                "page": page,
                "perPage": args.repo_page_size,
                "language": language,
                "checkedFrom": args.checked_from,
                "checkedTo": args.checked_to,
                "inCandidate": args.in_candidate,
            },
        )
        repos.extend(response.get("repositories") or [])
        if page >= int(response.get("totalPages") or 1):
            break
        page += 1
    return repos


def repos_by_language(args: argparse.Namespace) -> dict[str, list[dict[str, Any]]]:
    languages = parse_languages(args.languages)
    grouped: dict[str, list[dict[str, Any]]] = {}
    if languages:
        for language in languages:
            grouped[language] = [repo for repo in list_allowed_repos(args, language) if repo_name(repo)]
        return grouped
    for repo in list_allowed_repos(args, None):
        if repo_name(repo):
            grouped.setdefault(repo_language(repo), []).append(repo)
    return dict(sorted(grouped.items(), key=lambda item: item[0]))


def scan_candidates(args: argparse.Namespace, repo: str) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    page = 1
    while page <= args.candidate_pages_per_repo:
        response = get_json(
            args.base_url,
            "/api/v1/swe/github/pulls/merged-candidates",
            {
                "repo": repo,
                "limit": args.candidate_limit_per_repo,
                "days": args.scan_days,
                "page": page,
                "perPage": args.candidate_scan_page_size,
                "minGoldSourceFiles": args.min_gold_source_files,
                "maxGoldSourceFiles": args.max_gold_source_files,
                "minGoldLines": args.min_gold_lines,
                "maxGoldLines": args.max_gold_lines,
            },
        )
        candidates.extend(response.get("candidates") or [])
        if not response.get("hasMore"):
            break
        page += 1
    return candidates


def load_tasks(base_url: str) -> tuple[dict[int, dict[str, Any]], dict[str, dict[str, Any]]]:
    tasks = request_json(base_url, "GET", "/api/v1/swe/tasks")
    by_candidate: dict[int, dict[str, Any]] = {}
    by_pr: dict[str, dict[str, Any]] = {}
    if not isinstance(tasks, list):
        return by_candidate, by_pr
    for task in tasks:
        if not isinstance(task, dict):
            continue
        candidate_id = task.get("candidateId")
        if isinstance(candidate_id, int):
            by_candidate.setdefault(candidate_id, task)
        source_url = task.get("sourceUrl")
        if isinstance(source_url, str) and source_url:
            by_pr.setdefault(source_url, task)
    return by_candidate, by_pr


def existing_task_for(
    candidate: dict[str, Any],
    by_candidate: dict[int, dict[str, Any]],
    by_pr: dict[str, dict[str, Any]],
) -> dict[str, Any] | None:
    candidate_id = candidate.get("id")
    if isinstance(candidate_id, int) and candidate_id in by_candidate:
        return by_candidate[candidate_id]
    pr_url = candidate.get("prUrl")
    if isinstance(pr_url, str) and pr_url in by_pr:
        return by_pr[pr_url]
    return None


def create_or_reuse_task(args: argparse.Namespace, candidate: dict[str, Any], existing: dict[str, Any] | None) -> dict[str, Any] | None:
    if existing and args.reuse_existing_task:
        if args.skip_existing_active_task and str(existing.get("status") or "") in TASK_ACTIVE_STATUSES:
            return None
        if args.skip_existing_completed_task and str(existing.get("status") or "") in TASK_TERMINAL_STATUSES:
            return None
        return existing
    candidate_id = candidate.get("id")
    if not isinstance(candidate_id, int):
        raise RuntimeError(f"candidate has no backend id: {candidate}")
    if args.dry_run:
        return {"id": None, "candidateId": candidate_id, "taskName": task_name(candidate), "status": "DRY_RUN"}
    return post_json(
        args.base_url,
        "/api/v1/swe/tasks/from-candidate",
        {"candidateId": candidate_id, "taskName": task_name(candidate)},
    )


def candidate_to_csv_row(candidate: dict[str, Any]) -> dict[str, str]:
    row: dict[str, str] = {}
    for field in CANDIDATE_CSV_FIELDS:
        value = candidate.get(field)
        if value is None:
            value = candidate.get("id" if field == "candidate_id" else snake_to_camel(field))
        if isinstance(value, (dict, list)):
            row[field] = json.dumps(value, ensure_ascii=False)
        elif value is None:
            row[field] = ""
        else:
            row[field] = str(value)
    if not row.get("candidate_status"):
        row["candidate_status"] = "scored"
    if not row.get("notes"):
        strengths = candidate.get("strengths") or []
        risks = candidate.get("risks") or []
        row["notes"] = "; ".join([*(str(x) for x in strengths), *(f"risk: {x}" for x in risks)])
    return row


def snake_to_camel(name: str) -> str:
    parts = name.split("_")
    return parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])


def write_candidate_csv(candidate: dict[str, Any], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=CANDIDATE_CSV_FIELDS)
        writer.writeheader()
        writer.writerow(candidate_to_csv_row(candidate))


def package_matches_candidate(package_path: Path, candidate: dict[str, Any]) -> bool:
    task_json = package_path / "task.json"
    if not task_json.is_file():
        return False
    try:
        data = json.loads(task_json.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return False
    pr_url = str(candidate.get("prUrl") or "")
    metadata = data.get("metadata") if isinstance(data, dict) else {}
    links = data.get("evidence_links") if isinstance(data, dict) else []
    return (
        bool(pr_url)
        and (
            (isinstance(metadata, dict) and metadata.get("source_pr") == pr_url)
            or (isinstance(links, list) and pr_url in links)
            or data.get("source_pr") == pr_url
        )
    )


def find_prepared_package(out_root: Path, candidate: dict[str, Any], expected: Path) -> Path:
    if package_matches_candidate(expected, candidate):
        return expected
    if expected.is_dir() and (expected / "task.json").is_file():
        return expected
    for package_path in sorted(out_root.glob("production-task-*")):
        if package_matches_candidate(package_path, candidate):
            return package_path
    raise RuntimeError(
        f"prepared package not found for {candidate.get('repo')} #{candidate.get('number')} "
        f"under {out_root}; expected {expected}"
    )


def prepare_package(args: argparse.Namespace, candidate: dict[str, Any], item_dir: Path) -> Path:
    out_root = Path(args.package_root).expanduser().resolve()
    package_path = out_root / package_name(candidate)
    csv_path = item_dir / "candidate.csv"
    write_candidate_csv(candidate, csv_path)
    if args.dry_run:
        return package_path
    command = [
        sys.executable,
        str(PREPARE_SCRIPT),
        "--candidates",
        str(csv_path),
        "--out-root",
        str(out_root),
        "--min-score",
        str(args.min_score),
        "--statuses",
        args.candidate_statuses,
        "--limit",
        "1",
    ]
    if args.no_clone:
        command.append("--no-clone")
    if args.llm_oracle_review:
        command.append("--llm-oracle-review")
    subprocess.run(command, cwd=ROOT, check=True)
    return find_prepared_package(out_root, candidate, package_path)


def enqueue_item(
    state_file: Path,
    key: str,
    *,
    token: str,
    language: str,
    repo: str,
    candidate: dict[str, Any],
    task: dict[str, Any],
    package_path: Path,
    item_dir: Path,
    overwrite: bool,
) -> bool:
    def edit(state: dict[str, Any]) -> bool:
        items = state.setdefault("items", {})
        existing = items.get(key)
        if existing and existing.get("discover_token") != token:
            if not overwrite:
                return False
        if existing and not overwrite and existing.get("status") not in {"creating_task", "preparing_package", "failed", "skipped"}:
            return False
        items[key] = {
            "status": "pending_verifier",
            "discover_token": token,
            "discover_owner": existing.get("discover_owner") if isinstance(existing, dict) else None,
            "language": language,
            "repo": repo,
            "candidate_id": candidate.get("id"),
            "pr_url": candidate.get("prUrl"),
            "pr_number": candidate.get("number"),
            "task_id": task.get("id"),
            "task_name": task.get("taskName") or task_name(candidate),
            "task_status": task.get("status"),
            "package_path": str(package_path),
            "item_dir": str(item_dir),
            "created_at": now_utc(),
            "updated_at": now_utc(),
        }
        return True

    return bool(with_state_lock(state_file, edit))


def validation_ok(package_path: Path) -> bool:
    path = package_path / "logs" / "docker" / "validation.json"
    if not path.is_file():
        return False
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return False
    return bool(data.get("ok")) and validation_matches_task_spec(package_path, data)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def task_spec_checksums(package_path: Path) -> dict[str, str | None]:
    checksums: dict[str, str | None] = {}
    for relative in TASK_SPEC_FILES:
        path = package_path / relative
        checksums[relative] = sha256_file(path) if path.is_file() else None
    return checksums


def validation_matches_task_spec(package_path: Path, validation: dict[str, Any]) -> bool:
    recorded = validation.get("task_spec_checksums")
    if not isinstance(recorded, dict) or not recorded:
        return False
    for relative, checksum in task_spec_checksums(package_path).items():
        if recorded.get(relative) != checksum:
            return False
    return True


def run_id_from_handoff_response(text: Any) -> int | None:
    if not isinstance(text, str) or not text.strip():
        return None
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return None
    for item in data.get("responses") or []:
        if not isinstance(item, dict):
            continue
        response = item.get("response")
        if isinstance(response, dict) and isinstance(response.get("id"), int):
            return response["id"]
    return None


def path_is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def handoff_package_path(package_path: Path, handoff_root: str, *, copy: bool) -> Path:
    root = Path(handoff_root).expanduser().resolve()
    if path_is_relative_to(package_path, root):
        return package_path
    target = root / package_path.name
    if copy:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(package_path, target, dirs_exist_ok=True)
    return target.resolve()


def verifier_prompt(item: dict[str, Any], args: argparse.Namespace) -> str:
    package_path = item["package_path"]
    task_id = item.get("task_id")
    candidate_id = item.get("candidate_id")
    return (
        "使用 swe-pro-local-verifier 技能处理这个 SWE-Pro package。"
        f"package_dir={package_path}，task_id={task_id}，candidate_id={candidate_id}。"
        "严格按技能流程执行：先用 package_lock.py 做包目录独占，修正 gold.patch/test.patch/task.json/"
        "run_selected_tests.sh/Dockerfile/runtime_env.json，只在本地完成 patch、baseline、fixed、pass-to-pass "
        "Docker 验证；logs/docker/validation.json 的 ok 必须为 true。"
        "写 verification.md 和 .swe-ai/handoff.json，handoff.json 的 resume_from_stage=MODEL_OPUS_EVAL。"
        "不要直接启动后端全流程；本地验证通过后再由队列脚本执行 handoff。"
    )


def codex_command(item: dict[str, Any], args: argparse.Namespace) -> str:
    prompt = verifier_prompt(item, args)
    return " ".join([
        shlex.quote(args.codex_binary),
        "exec",
        "--cd",
        shlex.quote(str(ROOT)),
        shlex.quote(prompt),
    ])


def tmux_has_session(session: str) -> bool:
    return subprocess.run(
        ["tmux", "has-session", "-t", session],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    ).returncode == 0


def tmux_worker_shell_command(args: argparse.Namespace, worker_index: int) -> str:
    owner = f"{args.owner_prefix}-{worker_index}"
    claim_command = [
        shlex.quote(sys.executable),
        shlex.quote(str(Path(__file__).resolve())),
        "claim",
        "--state-file",
        shlex.quote(str(Path(args.state_file).expanduser().resolve())),
        "--languages",
        shlex.quote(args.languages),
        "--owner",
        shlex.quote(owner),
        "--codex-binary",
        shlex.quote(args.codex_binary),
    ]
    if args.base_url:
        claim_command += ["--base-url", shlex.quote(args.base_url)]
    claim_text = " ".join(claim_command)
    return "\n".join([
        f"cd {shlex.quote(str(ROOT))}",
        f"echo '[{owner}] claiming verifier work...'",
        f"CMD=$({claim_text})",
        "RC=$?",
        "printf '%s\\n' \"$CMD\"",
        "if [ \"$RC\" -eq 0 ] && [ -n \"$CMD\" ]; then",
        f"  echo '[{owner}] starting codex verifier...'",
        "  eval \"$CMD\"",
        "else",
        f"  echo '[{owner}] no verifier work claimed or claim failed (rc='\"$RC\"')'",
        "fi",
        "echo",
        f"echo '[{owner}] worker finished; leaving shell open for inspection.'",
        "exec bash",
    ])


def command_launch_tmux(args: argparse.Namespace) -> int:
    if shutil.which("tmux") is None:
        print("tmux is not installed or not on PATH", file=sys.stderr)
        return 1
    session_exists = tmux_has_session(args.session)
    created = 0
    for index in range(1, args.workers + 1):
        window_name = f"{args.window_prefix}-{index}"
        shell_command = tmux_worker_shell_command(args, index)
        if not session_exists and created == 0:
            subprocess.run(
                ["tmux", "new-session", "-d", "-s", args.session, "-n", window_name, "bash", "-lc", shell_command],
                check=True,
            )
            session_exists = True
        else:
            subprocess.run(
                ["tmux", "new-window", "-t", args.session, "-n", window_name, "bash", "-lc", shell_command],
                check=True,
            )
        created += 1
    print(json.dumps({
        "session": args.session,
        "workers_opened": created,
        "languages": args.languages,
        "attach_command": f"tmux attach -t {args.session}",
    }, ensure_ascii=False, indent=2))
    if args.attach:
        subprocess.run(["tmux", "attach", "-t", args.session], check=True)
    return 0


def command_discover(args: argparse.Namespace) -> int:
    state_file = Path(args.state_file).expanduser().resolve()
    grouped = repos_by_language(args)
    base_work_dir = Path(args.work_dir).expanduser().resolve()
    discover_owner = f"{socket.gethostname()}:{os.getpid()}"
    summary = {
        "mode": "discover",
        "checked_from": args.checked_from,
        "checked_to": args.checked_to,
        "languages": {},
        "enqueued": 0,
        "skipped": 0,
        "failed": 0,
        "dry_run": args.dry_run,
    }

    total = 0
    for language, repos in grouped.items():
        if args.max_repos_per_language > 0:
            repos = repos[: args.max_repos_per_language]
        lang_summary = summary["languages"].setdefault(language, {"repos": len(repos), "candidates": 0, "enqueued": 0})
        language_total = 0
        print(f"[{language}] allowed repos={len(repos)}", flush=True)
        for repo_row in repos:
            if args.max_total_candidates > 0 and total >= args.max_total_candidates:
                break
            if args.max_candidates_per_language > 0 and language_total >= args.max_candidates_per_language:
                break
            repo = repo_name(repo_row)
            try:
                candidates = scan_candidates(args, repo)
            except Exception as exc:
                summary["failed"] += 1
                print(f"[{language}] scan failed repo={repo}: {exc}", file=sys.stderr, flush=True)
                continue
            print(f"[{language}] repo={repo} candidates={len(candidates)}", flush=True)
            for candidate in candidates:
                if args.max_total_candidates > 0 and total >= args.max_total_candidates:
                    break
                if args.max_candidates_per_language > 0 and language_total >= args.max_candidates_per_language:
                    break
                key = candidate_key(candidate)
                if args.dry_run:
                    total += 1
                    language_total += 1
                    lang_summary["candidates"] += 1
                    lang_summary["enqueued"] += 1
                    summary["enqueued"] += 1
                    print(
                        f"[dry-run] would queue language={language} repo={repo} "
                        f"pr=#{candidate.get('number')} key={key} package={Path(args.package_root).expanduser().resolve() / package_name(candidate)}",
                        flush=True,
                    )
                    continue
                token = f"{discover_owner}:{time.time_ns()}"
                reserved = reserve_candidate_for_discover(
                    state_file,
                    key,
                    token=token,
                    language=language,
                    repo=repo,
                    candidate=candidate,
                    owner=discover_owner,
                    stale_minutes=args.stale_claim_minutes,
                    overwrite=args.overwrite_queue_item,
                )
                if not reserved:
                    summary["skipped"] += 1
                    continue
                try:
                    if args.reuse_existing_task:
                        by_candidate, by_pr = load_tasks(args.base_url)
                        existing = existing_task_for(candidate, by_candidate, by_pr)
                    else:
                        existing = None
                    task = create_or_reuse_task(args, candidate, existing)
                    if task is None:
                        update_reserved_item(
                            state_file,
                            key,
                            token,
                            status="skipped",
                            skip_reason="existing task is active or completed",
                        )
                        summary["skipped"] += 1
                        continue
                    update_reserved_item(
                        state_file,
                        key,
                        token,
                        status="preparing_package",
                        task_id=task.get("id"),
                        task_name=task.get("taskName") or task_name(candidate),
                        task_status=task.get("status"),
                    )
                    item_dir = base_work_dir / key.replace(":", "_").replace("/", "_").replace("#", "_")
                    package_path = prepare_package(args, candidate, item_dir)
                    enqueued = enqueue_item(
                        state_file,
                        key,
                        token=token,
                        language=language,
                        repo=repo,
                        candidate=candidate,
                        task=task,
                        package_path=package_path,
                        item_dir=item_dir,
                        overwrite=args.overwrite_queue_item,
                    )
                    if not enqueued:
                        summary["skipped"] += 1
                        continue
                    total += 1
                    language_total += 1
                    lang_summary["candidates"] += 1
                    lang_summary["enqueued"] += 1
                    summary["enqueued"] += 1
                    print(f"[{language}] queued repo={repo} pr=#{candidate.get('number')} package={package_path}", flush=True)
                except Exception as exc:
                    summary["failed"] += 1
                    update_reserved_item(state_file, key, token, status="failed", error=str(exc))
                    print(f"[{language}] enqueue failed key={key}: {exc}", file=sys.stderr, flush=True)

    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    return 1 if summary["failed"] else 0


def command_claim(args: argparse.Namespace) -> int:
    state_file = Path(args.state_file).expanduser().resolve()
    owner = args.owner or f"{socket.gethostname()}:{os.getpid()}"

    def claim(state: dict[str, Any]) -> dict[str, Any] | None:
        for key, item in sorted(state.get("items", {}).items()):
            if item.get("status") != "pending_verifier":
                continue
            if args.languages != "all" and item.get("language") not in parse_languages(args.languages):
                continue
            item["status"] = "claimed_verifier"
            item["owner"] = owner
            item["claimed_at"] = now_utc()
            item["updated_at"] = now_utc()
            return {"key": key, **item}
        return None

    item = with_state_lock(state_file, claim)
    if item is None:
        print("no pending verifier item", file=sys.stderr)
        return 2
    if args.json:
        print(json.dumps(item, ensure_ascii=False, indent=2))
    elif args.print_prompt:
        print(verifier_prompt(item, args))
    else:
        print(codex_command(item, args))
    return 0


def command_handoff(args: argparse.Namespace) -> int:
    state_file = Path(args.state_file).expanduser().resolve()
    selected: list[tuple[str, dict[str, Any]]] = []

    def collect(state: dict[str, Any]) -> list[tuple[str, dict[str, Any]]]:
        result: list[tuple[str, dict[str, Any]]] = []
        ready_statuses = set(READY_VALIDATION_STATUSES)
        if args.rehandoff:
            ready_statuses.add("handed_off")
            ready_statuses.add("handoff_dry_run")
        for key, item in sorted(state.get("items", {}).items()):
            if args.key and key != args.key:
                continue
            if item.get("status") not in ready_statuses:
                continue
            package_path = Path(str(item.get("package_path") or ""))
            if validation_ok(package_path):
                result.append((key, dict(item)))
                if args.key or args.one:
                    break
        return result

    selected = with_state_lock(state_file, collect)
    if not selected:
        print("no locally verified package ready for handoff", file=sys.stderr)
        return 2

    failures = 0
    for key, item in selected:
        original_package_path = Path(item["package_path"]).resolve()
        package_path = handoff_package_path(original_package_path, args.handoff_root, copy=False)
        task_id = item.get("task_id")
        candidate_id = item.get("candidate_id")
        run_id = item.get("run_id") or run_id_from_handoff_response(item.get("handoff_response"))
        if not task_id:
            print(f"{key}: missing task_id", file=sys.stderr)
            failures += 1
            continue
        if not args.skip_qc_check:
            subprocess.run([sys.executable, str(QC_SCRIPT), str(original_package_path), "--check-only"], cwd=ROOT, check=True)
        command = [
            sys.executable,
            str(HANDOFF_SCRIPT),
            "--base-url",
            args.base_url,
            "--package-dir",
            str(original_package_path),
            "--task-id",
            str(task_id),
            "--resume-from-stage",
            args.resume_from_stage,
            "--handoff-root",
            args.handoff_root,
        ]
        if candidate_id:
            command += ["--candidate-id", str(candidate_id)]
        if run_id:
            command += ["--run-id", str(run_id)]
        if args.dry_run:
            command.append("--dry-run")
        try:
            proc = subprocess.run(command, cwd=ROOT, check=True, text=True, capture_output=True)
            response = proc.stdout.strip()
            response_run_id = run_id_from_handoff_response(response) or run_id
            update_item(
                state_file,
                key,
                status="handoff_dry_run" if args.dry_run else "handed_off",
                handoff_package_path=str(package_path),
                run_id=response_run_id,
                handoff_response=response,
            )
            print(response)
        except subprocess.CalledProcessError as exc:
            failures += 1
            update_item(state_file, key, status="handoff_failed", error=exc.stderr or exc.stdout or str(exc))
            print(exc.stderr or exc.stdout or str(exc), file=sys.stderr)
    return 1 if failures else 0


def add_common_api_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base-url", default=os.environ.get("SWE_BACKEND_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--state-file", default=DEFAULT_STATE_FILE)


def validate_discover_args(parser: argparse.ArgumentParser, args: argparse.Namespace) -> None:
    languages = parse_languages(args.languages)
    if not languages:
        parser.error("discover requires explicit --languages, for example --languages go or --languages go,python; 'all' is not allowed")
    if args.max_repos_per_language <= 0:
        parser.error("discover requires --max-repos-per-language > 0")
    if args.candidate_limit_per_repo <= 0:
        parser.error("discover requires --candidate-limit-per-repo > 0")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="SWE-Pro local-verifier queue dispatcher.")
    sub = parser.add_subparsers(dest="command", required=True)

    today = today_text()
    discover = sub.add_parser("discover", help="Discover candidates and enqueue package-local verifier work.")
    add_common_api_args(discover)
    discover.add_argument("--checked-from", default=today)
    discover.add_argument("--checked-to", default=today)
    discover.add_argument("--languages", required=True)
    discover.add_argument("--in-candidate", action=argparse.BooleanOptionalAction, default=None)
    discover.add_argument("--repo-page-size", type=positive_int, default=100)
    discover.add_argument("--max-repos-per-language", type=positive_int, required=True)
    discover.add_argument("--max-candidates-per-language", type=non_negative_int, default=0)
    discover.add_argument("--max-total-candidates", type=non_negative_int, default=0)
    discover.add_argument("--scan-days", type=positive_int, default=365)
    discover.add_argument("--candidate-limit-per-repo", type=positive_int, required=True)
    discover.add_argument("--candidate-pages-per-repo", type=positive_int, default=1)
    discover.add_argument("--candidate-scan-page-size", type=positive_int, default=10)
    discover.add_argument("--min-gold-source-files", type=int)
    discover.add_argument("--max-gold-source-files", type=int)
    discover.add_argument("--min-gold-lines", type=int)
    discover.add_argument("--max-gold-lines", type=int)
    discover.add_argument("--reuse-existing-task", action=argparse.BooleanOptionalAction, default=True)
    discover.add_argument("--skip-existing-active-task", action=argparse.BooleanOptionalAction, default=True)
    discover.add_argument("--skip-existing-completed-task", action=argparse.BooleanOptionalAction, default=True)
    discover.add_argument("--package-root", default=DEFAULT_PACKAGE_ROOT)
    discover.add_argument("--work-dir", default=".swe-ai/verifier-dispatch")
    discover.add_argument("--min-score", type=int, default=70)
    discover.add_argument("--candidate-statuses", default="scored,selected")
    discover.add_argument("--no-clone", action="store_true")
    discover.add_argument("--llm-oracle-review", action="store_true")
    discover.add_argument("--overwrite-queue-item", action="store_true")
    discover.add_argument("--stale-claim-minutes", type=non_negative_int, default=240)
    discover.add_argument("--dry-run", action="store_true")
    discover.set_defaults(func=command_discover)

    claim = sub.add_parser("claim", help="Claim one pending package for a CLI verifier window.")
    add_common_api_args(claim)
    claim.add_argument("--languages", default="all")
    claim.add_argument("--owner", default="")
    claim.add_argument("--codex-binary", default="codex")
    claim.add_argument("--json", action="store_true")
    claim.add_argument("--print-prompt", action="store_true")
    claim.set_defaults(func=command_claim)

    launch = sub.add_parser("launch-tmux", help="Open N tmux worker windows that claim and run verifier work.")
    add_common_api_args(launch)
    launch.add_argument("--workers", type=positive_int, required=True)
    launch.add_argument("--languages", default="all")
    launch.add_argument("--session", default="swe-verifier")
    launch.add_argument("--window-prefix", default="worker")
    launch.add_argument("--owner-prefix", default="worker")
    launch.add_argument("--codex-binary", default="codex")
    launch.add_argument("--attach", action="store_true")
    launch.set_defaults(func=command_launch_tmux)

    handoff = sub.add_parser("handoff", help="Handoff locally verified packages to backend from MODEL_OPUS_EVAL.")
    add_common_api_args(handoff)
    handoff.add_argument("--key", default="")
    handoff.add_argument("--one", action="store_true")
    handoff.add_argument("--resume-from-stage", default="MODEL_OPUS_EVAL")
    handoff.add_argument("--handoff-root", default=DEFAULT_HANDOFF_ROOT)
    handoff.add_argument("--rehandoff", action="store_true", help="Allow re-handoff of items already marked handed_off.")
    handoff.add_argument("--skip-qc-check", action="store_true")
    handoff.add_argument("--dry-run", action="store_true")
    handoff.set_defaults(func=command_handoff)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    if args.command == "discover":
        validate_discover_args(parser, args)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
