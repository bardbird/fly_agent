#!/usr/bin/env bash
set -euo pipefail

python - <<'PY'
import json
import re
from collections import Counter
from pathlib import Path

line_re = re.compile(
    r'^(?P<host>\S+) \S+ \S+ \[[^\]]+\] "(?P<method>\S+) (?P<path>\S+) HTTP/[^"]+" (?P<status>\d{3}) (?P<bytes>\d+|-)$'
)

total = 0
clients = set()
statuses = Counter()
paths = Counter()
largest = 0

for raw in Path("/app/data/access.log").read_text().splitlines():
    if not raw.strip():
        continue
    match = line_re.match(raw)
    if not match:
        raise SystemExit(f"Malformed log line: {raw}")
    total += 1
    clients.add(match["host"])
    statuses[match["status"]] += 1
    paths[match["path"]] += 1
    size = 0 if match["bytes"] == "-" else int(match["bytes"])
    largest = max(largest, size)

top_paths = [
    {"path": path, "count": count}
    for path, count in sorted(paths.items(), key=lambda item: (-item[1], item[0]))[:3]
]

report = {
    "largest_response_bytes": largest,
    "status_counts": {code: statuses[code] for code in sorted(statuses)},
    "top_paths": top_paths,
    "total_requests": total,
    "unique_clients": len(clients),
}

Path("/app/report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
PY
