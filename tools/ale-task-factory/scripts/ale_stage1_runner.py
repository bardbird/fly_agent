#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


@dataclass
class TaskRequest:
    domain: str
    task_id: str
    title: str
    scenario: str
    difficulty: str
    input_mode: str
    output_mode: str
    verification_mode: str
    reference_strategy: str
    note: str = ""


@dataclass
class TaskResult:
    task_id: str
    domain: str
    status: str
    score: float | None = None
    reason: str | None = None
    task_dir: str | None = None
    evidence_path: str | None = None


def build_plan(request: TaskRequest) -> dict[str, Any]:
    task_dir = Path("tasks") / request.domain / request.task_id
    return {
        "task": asdict(request),
        "task_dir": str(task_dir),
        "stages": [
            "brief",
            "draft",
            "scaffold",
            "oracle_validate",
        ],
        "requirements": {
            "oracle_must_pass": True,
            "llm_call_count": 0,
            "reference_hidden": True,
        },
    }


def run_codex(plan_path: Path, output_dir: Path, cwd: Path) -> subprocess.CompletedProcess[str]:
    output_dir.mkdir(parents=True, exist_ok=True)
    log_path = output_dir / "codex.log"
    cmd = [
        "codex",
        "exec",
        "-C",
        str(cwd),
        "--sandbox",
        "danger-full-access",
        "--ask-for-approval",
        "never",
        f"Use the ALE task factory skill to produce the batch described in {plan_path}. Write all generated artifacts under {output_dir}.",
    ]
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"$ {' '.join(cmd)}\n")
        log.flush()
        proc = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            check=False,
        )
    return proc


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--domain", required=True)
    parser.add_argument("--task-id", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--difficulty", required=True, choices=["easy", "medium", "hard"])
    parser.add_argument("--input-mode", required=True)
    parser.add_argument("--output-mode", required=True)
    parser.add_argument("--verification-mode", required=True)
    parser.add_argument("--reference-strategy", required=True)
    parser.add_argument("--note", default="")
    parser.add_argument("--output-root", required=True)
    args = parser.parse_args()

    request = TaskRequest(
        domain=args.domain,
        task_id=args.task_id,
        title=args.title,
        scenario=args.scenario,
        difficulty=args.difficulty,
        input_mode=args.input_mode,
        output_mode=args.output_mode,
        verification_mode=args.verification_mode,
        reference_strategy=args.reference_strategy,
        note=args.note,
    )

    run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output_root = Path(args.output_root) / f"{args.domain}__{args.task_id}__{run_id}"
    output_root.mkdir(parents=True, exist_ok=True)

    request_path = output_root / "request.json"
    plan_path = output_root / "plan.json"
    request_path.write_text(json.dumps(asdict(request), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    plan_path.write_text(json.dumps(build_plan(request), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    proc = run_codex(plan_path, output_root, Path.cwd())
    status = "completed" if proc.returncode == 0 else "failed"
    summary = {
        "run_id": run_id,
        "domain": request.domain,
        "task_id": request.task_id,
        "status": status,
        "task_dir": str(Path("tasks") / request.domain / request.task_id),
        "outputs": [str(request_path), str(plan_path), str(output_root / "codex.log")],
        "command_exit_code": proc.returncode,
    }
    (output_root / "summary.json").write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
