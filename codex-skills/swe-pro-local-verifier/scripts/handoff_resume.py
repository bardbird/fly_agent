#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import urllib.error
import urllib.request
from pathlib import Path


def request_json(base_url: str, method: str, path: str, payload: dict | None = None) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {exc.code} {path}: {body}") from exc
    return json.loads(body)


def unwrap(result: dict) -> dict:
    if isinstance(result, dict) and result.get("code") not in (None, "SUCCESS"):
        raise SystemExit(
            f"backend error {result.get('code')}: {result.get('message') or result}"
        )
    if isinstance(result, dict) and "data" in result:
        return result["data"]
    return result


def request_runs(base_url: str, task_id: int) -> list[dict]:
    result = unwrap(request_json(base_url, "GET", f"/api/v1/swe/runs?taskId={task_id}"))
    return result if isinstance(result, list) else []


def latest_resumable_run_id(base_url: str, task_id: int) -> int | None:
    terminal_or_busy = {"RUNNING", "COMPLETED"}
    for run in request_runs(base_url, task_id):
        if not isinstance(run, dict):
            continue
        if str(run.get("status") or "") in terminal_or_busy:
            continue
        run_id = run.get("id")
        if isinstance(run_id, int):
            return run_id
    return None


def path_is_relative_to(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def parse_visible_roots(values: list[str]) -> list[Path]:
    roots: list[Path] = []
    for value in values:
        for raw in value.split(":"):
            if raw.strip():
                roots.append(Path(raw).expanduser().resolve())
    return roots


def mirror_to_handoff_root(package_path: Path, handoff_root: str, *, copy: bool) -> Path:
    root = Path(handoff_root).expanduser().resolve()
    if path_is_relative_to(package_path, root):
        return package_path
    target = root / package_path.name
    if copy:
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(package_path, target, dirs_exist_ok=True)
    return target.resolve()


def require_backend_visible(package_path: Path, visible_roots: list[Path]) -> None:
    if not visible_roots:
        return
    if any(path_is_relative_to(package_path, root) for root in visible_roots):
        return
    roots_text = ", ".join(str(root) for root in visible_roots)
    raise SystemExit(
        f"package-dir is not under a backend-visible root: {package_path}. "
        f"Visible roots: {roots_text}. Use --handoff-root to mirror it first "
        "or --skip-backend-path-check if the backend can see this path."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Create/resume fly-agent SWE-Pro backend work after local verification.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--package-dir")
    parser.add_argument("--task-id", type=int)
    parser.add_argument("--candidate-id", type=int)
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--task-name", default="")
    parser.add_argument("--resume-from-stage", default="MODEL_OPUS_EVAL")
    parser.add_argument(
        "--force-resume",
        action="store_true",
        help="Ask backend to replay an existing RUNNING run, for recovery after a lost async worker.",
    )
    parser.add_argument(
        "--auto-resume-latest-run",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="When --run-id is omitted, resume the latest non-running/non-completed run for task-id if one exists.",
    )
    parser.add_argument(
        "--handoff-root",
        default="",
        help="Mirror package-dir here before handoff when package-dir is outside backend-visible roots.",
    )
    parser.add_argument(
        "--backend-visible-root",
        action="append",
        default=[],
        help="Path prefix visible to the backend container/process. Defaults to SWE_BACKEND_VISIBLE_ROOTS or /data/fly-agent/swe-output.",
    )
    parser.add_argument("--skip-backend-path-check", action="store_true")
    parser.add_argument("--create-task", action="store_true")
    parser.add_argument("--from-candidate", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    calls: list[dict] = []
    task_id = args.task_id
    package_path = Path(args.package_dir).expanduser().resolve() if args.package_dir else None
    if package_path and not package_path.is_dir():
        raise SystemExit(f"package-dir not found: {package_path}")
    if package_path and args.handoff_root:
        package_path = mirror_to_handoff_root(package_path, args.handoff_root, copy=not args.dry_run)
    visible_root_values = args.backend_visible_root or [os.environ.get("SWE_BACKEND_VISIBLE_ROOTS", "/data/fly-agent/swe-output")]
    visible_roots = [] if args.skip_backend_path_check else parse_visible_roots(visible_root_values)
    if package_path:
        require_backend_visible(package_path, visible_roots)
    package_path_text = str(package_path) if package_path else ""

    if args.create_task and args.run_id:
        raise SystemExit("--create-task cannot be combined with --run-id; resumeRunId must belong to an existing task")
    if not args.run_id and task_id and args.auto_resume_latest_run and not args.dry_run:
        args.run_id = latest_resumable_run_id(args.base_url, task_id)

    if args.create_task:
        if args.from_candidate:
            if not args.candidate_id:
                raise SystemExit("--candidate-id is required with --from-candidate")
            payload = {"candidateId": args.candidate_id}
            if args.task_name:
                payload["taskName"] = args.task_name
            calls.append({"method": "POST", "path": "/api/v1/swe/tasks/from-candidate", "payload": payload})
        else:
            if not args.package_dir:
                raise SystemExit("--package-dir is required when creating a task from a package")
            payload = {
                "taskName": args.task_name or Path(package_path_text).name,
                "samplePath": package_path_text,
            }
            calls.append({"method": "POST", "path": "/api/v1/swe/tasks", "payload": payload})

    if args.run_id:
        if not task_id and not args.create_task:
            raise SystemExit("--task-id is required when resuming an existing run without --create-task")
        payload = {
            "taskId": task_id or "<created-task-id>",
            "resumeRunId": args.run_id,
            "resumeFromStage": args.resume_from_stage,
        }
        if args.force_resume:
            payload["forceResume"] = True
        if package_path_text:
            payload["samplePath"] = package_path_text
        calls.append({"method": "POST", "path": "/api/v1/swe/runs/start", "payload": payload})
    elif task_id or args.create_task:
        payload = {
            "taskId": task_id or "<created-task-id>",
            "resumeFromStage": args.resume_from_stage,
        }
        if package_path_text:
            payload["samplePath"] = package_path_text
        calls.append({"method": "POST", "path": "/api/v1/swe/runs/start", "payload": payload})

    if args.dry_run:
        print(json.dumps({"expected_calls": len(calls), "calls": calls}, ensure_ascii=False, indent=2))
        return 0

    responses = []
    for call in calls:
        payload = dict(call["payload"])
        if payload.get("taskId") == "<created-task-id>":
            if not task_id:
                raise SystemExit("internal error: task id placeholder was not resolved")
            payload["taskId"] = task_id
        result = unwrap(request_json(args.base_url, call["method"], call["path"], payload))
        responses.append({"call": call["path"], "response": result})
        if call["path"].endswith("/tasks") or call["path"].endswith("/tasks/from-candidate"):
            task_id = int(result["id"])

    print(json.dumps({"calls": len(responses), "responses": responses}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
