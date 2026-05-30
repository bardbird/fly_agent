#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
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
    if isinstance(result, dict) and "data" in result:
        return result["data"]
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="Create/resume fly-agent SWE-Pro backend work after local verification.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--package-dir")
    parser.add_argument("--task-id", type=int)
    parser.add_argument("--candidate-id", type=int)
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--task-name", default="")
    parser.add_argument("--resume-from-stage", default="MODEL_OPUS_EVAL")
    parser.add_argument("--create-task", action="store_true")
    parser.add_argument("--from-candidate", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    calls: list[dict] = []
    task_id = args.task_id

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
            package = Path(args.package_dir).resolve()
            payload = {
                "taskName": args.task_name or package.name,
                "samplePath": str(package),
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
        calls.append({"method": "POST", "path": "/api/v1/swe/runs/start", "payload": payload})
    elif task_id or args.create_task:
        payload = {"taskId": task_id or "<created-task-id>"}
        if args.package_dir:
            payload["samplePath"] = str(Path(args.package_dir).resolve())
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
