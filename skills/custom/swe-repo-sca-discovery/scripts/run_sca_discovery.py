#!/usr/bin/env python3
"""Generate and optionally trigger sweRepoScaDiscoveryJob payloads."""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
from datetime import date, timedelta
from pathlib import Path
from urllib import request as urllib_request
from urllib.error import HTTPError, URLError


DEFAULT_TOTAL = 2000
DEFAULT_MIN_STARS = 8000
MAX_REPO_LIMIT = 200
PER_PAGE = 50

LANGUAGE_QUOTAS = [
    ("go", 400),
    ("python", 400),
    ("javascript", 200),
    ("typescript", 200),
    ("c", 100),
    ("c++", 100),
    ("java", 200),
    ("rust", 200),
    ("ruby", 100),
    ("php", 100),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--total", type=int, default=DEFAULT_TOTAL)
    parser.add_argument("--min-stars", type=int, default=DEFAULT_MIN_STARS)
    parser.add_argument("--scan-date-start", default=date.today().isoformat())
    parser.add_argument("--github-token", default=os.getenv("GITHUB_TOKEN") or os.getenv("GH_TOKEN") or "")
    parser.add_argument("--output", default="/tmp/swe_repo_sca_discovery_payloads.jsonl")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--executor-url", default="http://127.0.0.1:9999")
    parser.add_argument("--access-token", default=os.getenv("XXL_JOB_ACCESS_TOKEN") or "default_token")
    parser.add_argument("--job-id", type=int, default=260530)
    parser.add_argument("--sleep-seconds", type=float, default=0.2)
    return parser.parse_args()


def scaled_quotas(total: int) -> list[tuple[str, int]]:
    if total == DEFAULT_TOTAL:
        return LANGUAGE_QUOTAS
    scaled: list[tuple[str, int]] = []
    remaining = total
    for index, (language, quota) in enumerate(LANGUAGE_QUOTAS):
        if index == len(LANGUAGE_QUOTAS) - 1:
            count = remaining
        else:
            count = round(quota * total / DEFAULT_TOTAL)
            remaining -= count
        scaled.append((language, max(count, 0)))
    return scaled


def build_payloads(args: argparse.Namespace) -> list[dict[str, object]]:
    start_date = date.fromisoformat(args.scan_date_start)
    payloads: list[dict[str, object]] = []
    for language, quota in scaled_quotas(args.total):
        remaining = quota
        batch_index = 0
        while remaining > 0:
            repo_limit = min(remaining, MAX_REPO_LIMIT)
            executor_params = {
                "githubToken": args.github_token or "${GITHUB_TOKEN}",
                "languages": [language],
                "minStars": args.min_stars,
                "repositoryPerPage": PER_PAGE,
                "repositoryPages": math.ceil(repo_limit / PER_PAGE),
                "repoLimit": repo_limit,
                "useStarCursor": True,
                "scanDate": (start_date + timedelta(days=batch_index)).isoformat(),
            }
            payloads.append(
                {
                    "language": language,
                    "quota": quota,
                    "batchIndex": batch_index + 1,
                    "executorHandler": "sweRepoScaDiscoveryJob",
                    "executorParams": executor_params,
                }
            )
            batch_index += 1
            remaining -= repo_limit
    return payloads


def trigger_payload(args: argparse.Namespace, payload: dict[str, object], index: int) -> dict[str, object]:
    trigger_param = {
        "jobId": args.job_id,
        "executorHandler": payload["executorHandler"],
        "executorParams": json.dumps(payload["executorParams"], separators=(",", ":")),
        "executorBlockStrategy": "SERIAL_EXECUTION",
        "executorTimeout": 0,
        "logId": int(time.time() * 1000) + index,
        "logDateTime": int(time.time() * 1000),
        "glueType": "BEAN",
        "glueSource": "",
        "glueUpdatetime": 0,
        "broadcastIndex": 0,
        "broadcastTotal": 1,
    }
    body = json.dumps(trigger_param).encode("utf-8")
    endpoint = args.executor_url.rstrip("/") + "/run"
    req = urllib_request.Request(
        endpoint,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "XXL-JOB-ACCESS-TOKEN": args.access_token,
        },
    )
    try:
        with urllib_request.urlopen(req, timeout=10) as response:
            raw = response.read().decode("utf-8", errors="replace")
            return {"ok": True, "status": response.status, "body": parse_json(raw)}
    except HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return {"ok": False, "status": exc.code, "body": parse_json(raw)}
    except URLError as exc:
        return {"ok": False, "error": str(exc.reason)}


def parse_json(raw: str) -> object:
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


def main() -> int:
    args = parse_args()
    payloads = build_payloads(args)

    if args.execute and not args.github_token:
        print("Refusing to execute: GITHUB_TOKEN/GH_TOKEN or --github-token is required.", file=sys.stderr)
        write_payloads(args.output, payloads)
        return 2

    rows = []
    for index, payload in enumerate(payloads, start=1):
        row = dict(payload)
        if args.execute:
            row["triggerResponse"] = trigger_payload(args, payload, index)
            time.sleep(max(args.sleep_seconds, 0))
        rows.append(row)

    write_payloads(args.output, rows)
    print(f"wrote {len(rows)} payloads to {args.output}")
    if args.execute:
        failed = [row for row in rows if not row.get("triggerResponse", {}).get("ok")]
        if failed:
            print(f"{len(failed)} trigger requests failed", file=sys.stderr)
            return 1
        print(f"triggered {len(rows)} sweRepoScaDiscoveryJob payloads")
    return 0


def write_payloads(output: str, rows: list[dict[str, object]]) -> None:
    path = Path(output)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())

