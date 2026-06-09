#!/usr/bin/env bash
set -euo pipefail

python - <<'PY'
import gzip
import json
import re
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from urllib.parse import urlsplit

common_re = re.compile(
    r'^(?P<host>\S+) \S+ \S+ \[(?P<time>[^\]]+)\] "(?P<method>\S+) (?P<target>\S+) HTTP/[^"]+" (?P<status>\d{3}) (?P<bytes>\d+|-)$'
)
combined_re = re.compile(
    r'^(?P<host>\S+) \S+ \S+ \[(?P<time>[^\]]+)\] "(?P<method>\S+) (?P<target>\S+) HTTP/[^"]+" (?P<status>\d{3}) (?P<bytes>\d+|-) "[^"]*" "[^"]*"$'
)

def read_lines(path):
    if path.suffix == ".gz":
        with gzip.open(path, "rt") as handle:
            yield from handle
    else:
        yield from path.read_text().splitlines()

def parse_line(raw):
    return combined_re.match(raw) or common_re.match(raw)

def bucket_start(apache_time):
    dt = datetime.strptime(apache_time, "%d/%b/%Y:%H:%M:%S %z")
    minute = dt.minute - (dt.minute % 5)
    return dt.replace(minute=minute, second=0, microsecond=0).isoformat()

parsed = 0
skipped = 0
families = Counter({"2xx": 0, "3xx": 0, "4xx": 0, "5xx": 0})
path_errors = Counter()
clients = defaultdict(lambda: {"requests": 0, "errors": 0, "server_errors": 0, "total_bytes": 0})
windows = defaultdict(lambda: {"request_count": 0, "error_count": 0})

for path in sorted(Path("/app/logs").glob("access.log*")):
    for raw in read_lines(path):
        raw = raw.rstrip("\n")
        if not raw.strip():
            continue
        match = parse_line(raw)
        if not match:
            skipped += 1
            continue

        parsed += 1
        status = int(match["status"])
        family = f"{status // 100}xx"
        if family in families:
            families[family] += 1
        size = 0 if match["bytes"] == "-" else int(match["bytes"])
        normalized = urlsplit(match["target"]).path or "/"
        is_error = 400 <= status <= 599
        is_server_error = 500 <= status <= 599

        if is_error:
            path_errors[normalized] += 1

        host = match["host"]
        clients[host]["requests"] += 1
        clients[host]["total_bytes"] += size
        if is_error:
            clients[host]["errors"] += 1
        if is_server_error:
            clients[host]["server_errors"] += 1

        bucket = bucket_start(match["time"])
        windows[bucket]["request_count"] += 1
        if is_error:
            windows[bucket]["error_count"] += 1

top_error_paths = [
    {"path": path, "error_requests": count}
    for path, count in sorted(path_errors.items(), key=lambda item: (-item[1], item[0]))[:5]
]

client_incidents = []
for host, values in clients.items():
    if values["errors"] >= 3 or values["server_errors"] >= 2:
        client_incidents.append({"host": host, **values})
client_incidents.sort(key=lambda item: (-item["errors"], -item["requests"], item["host"]))

five_minute_error_windows = [
    {"window_start": bucket, **values}
    for bucket, values in windows.items()
    if values["error_count"] >= 2
]
five_minute_error_windows.sort(key=lambda item: item["window_start"])

report = {
    "client_incidents": client_incidents,
    "five_minute_error_windows": five_minute_error_windows,
    "parsed_lines": parsed,
    "skipped_lines": skipped,
    "status_families": {key: families[key] for key in ["2xx", "3xx", "4xx", "5xx"]},
    "top_error_paths": top_error_paths,
}

Path("/app/incident_report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
PY
