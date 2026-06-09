#!/usr/bin/env bash
set -euo pipefail

python - <<'PY'
import csv
import gzip
import json
import re
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlsplit

line_re = re.compile(
    r'^(?P<host>\S+) \S+ \S+ \[[^\]]+\] "(?P<method>\S+) (?P<target>\S+) HTTP/[^"]+" (?P<status>\d{3}) (?P<bytes>\d+|-) "[^"]*" "[^"]*"$'
)

def read_lines(path):
    if path.suffix == ".gz":
        with gzip.open(path, "rt") as handle:
            yield from handle
    else:
        yield from path.read_text().splitlines()

paths = defaultdict(lambda: {"total_requests": 0, "error_requests": 0, "total_bytes": 0})
clients = defaultdict(lambda: {"total_requests": 0, "client_error_requests": 0, "server_error_requests": 0})

for path in sorted(Path("/app/logs").glob("access.log*")):
    for raw in read_lines(path):
        raw = raw.rstrip("\n")
        if not raw.strip():
            continue
        match = line_re.match(raw)
        if not match:
            raise SystemExit(f"Malformed log line: {raw}")
        status = int(match["status"])
        size = 0 if match["bytes"] == "-" else int(match["bytes"])
        normalized = urlsplit(match["target"]).path or "/"
        paths[normalized]["total_requests"] += 1
        paths[normalized]["total_bytes"] += size
        if 400 <= status <= 599:
            paths[normalized]["error_requests"] += 1

        host = match["host"]
        clients[host]["total_requests"] += 1
        if 400 <= status <= 499:
            clients[host]["client_error_requests"] += 1
        elif 500 <= status <= 599:
            clients[host]["server_error_requests"] += 1

out_dir = Path("/app/reports")
out_dir.mkdir(parents=True, exist_ok=True)

with (out_dir / "traffic_summary.csv").open("w", newline="") as handle:
    writer = csv.DictWriter(
        handle,
        fieldnames=["path", "total_requests", "error_requests", "total_bytes"],
        lineterminator="\n",
    )
    writer.writeheader()
    for path, values in sorted(paths.items(), key=lambda item: (-item[1]["total_requests"], item[0])):
        writer.writerow({"path": path, **values})

anomalous = []
for host, values in sorted(clients.items()):
    if values["client_error_requests"] >= 3 or values["server_error_requests"] >= 2:
        anomalous.append({"host": host, **values})

(out_dir / "error_clients.json").write_text(
    json.dumps({"anomalous_clients": anomalous}, indent=2, sort_keys=True) + "\n"
)
PY
