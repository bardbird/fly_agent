#!/usr/bin/env bash
set -euo pipefail

APP_ROOT="${APP_ROOT:-/app}"
MAIN_DB="$APP_ROOT/backup/app.db"
WAL_DB="$APP_ROOT/recovered_wal/app.db-wal"
OUT_DIR="$APP_ROOT/output"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

mkdir -p "$OUT_DIR"
cp "$MAIN_DB" "$WORK_DIR/app.db"
cp "$WAL_DB" "$WORK_DIR/app.db-wal"

sqlite3 "$WORK_DIR/app.db" "PRAGMA wal_checkpoint(FULL);" >/dev/null
sqlite3 "$WORK_DIR/app.db" "VACUUM INTO '$OUT_DIR/recovered.db';"

APP_ROOT="$APP_ROOT" python3 - <<'PY'
import hashlib
import json
import os
import sqlite3
from pathlib import Path

root = Path(os.environ.get("APP_ROOT", "/app"))
db_path = root / "output" / "recovered.db"
report_path = root / "output" / "recovery_report.json"

conn = sqlite3.connect(db_path)
integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
tables = {}
for table in ("orders", "audit_log"):
    tables[table] = conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
main_count = sqlite3.connect(root / "backup" / "app.db").execute(
    "SELECT COUNT(*) FROM orders"
).fetchone()[0]
conn.close()

digest = hashlib.sha256(db_path.read_bytes()).hexdigest()
report = {
    "integrity_check": integrity,
    "wal_applied": tables["orders"] > main_count,
    "tables": tables,
    "recovered_db_sha256": digest,
}
report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
