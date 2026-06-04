import hashlib
import json
import os
import re
import sqlite3
import traceback
from pathlib import Path


APP_ROOT = Path(os.environ.get("APP_ROOT", "/app"))
DB_PATH = APP_ROOT / "output" / "recovered.db"
REPORT_PATH = APP_ROOT / "output" / "recovery_report.json"


def _connect():
    assert DB_PATH.is_file(), f"missing recovered database: {DB_PATH}"
    return sqlite3.connect(DB_PATH)


def test_output_files_exist():
    assert DB_PATH.is_file(), "recovered.db was not created"
    assert REPORT_PATH.is_file(), "recovery_report.json was not created"


def test_recovered_database_is_clean_and_integral():
    conn = _connect()
    assert conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    conn.close()
    assert not (APP_ROOT / "output" / "recovered.db-wal").exists()
    assert not (APP_ROOT / "output" / "recovered.db-shm").exists()


def test_committed_wal_rows_and_update_are_recovered():
    conn = _connect()
    rows = conn.execute(
        "SELECT id, customer, amount_cents, status FROM orders ORDER BY id"
    ).fetchall()
    conn.close()
    assert rows == [
        (1001, "Ada Lovelace", 12450, "paid"),
        (1002, "Ben Nassar", 8799, "shipped"),
        (1003, "Cai Ren", 500, "cancelled"),
        (1004, "Dana Ortiz", 21990, "paid"),
        (1005, "Eli Park", 4310, "review"),
        (1006, "Fay Zhou", 15200, "paid"),
    ]


def test_audit_log_contains_post_backup_events():
    conn = _connect()
    count = conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]
    status_event = conn.execute(
        "SELECT detail FROM audit_log WHERE event_type = 'status_changed' AND entity_id = 1002"
    ).fetchone()
    conn.close()
    assert count == 7
    assert status_event == ("pending->shipped from recovered WAL",)


def test_report_matches_recovered_database():
    report = json.loads(REPORT_PATH.read_text(encoding="utf-8"))
    conn = _connect()
    order_count = conn.execute("SELECT COUNT(*) FROM orders").fetchone()[0]
    audit_count = conn.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]
    conn.close()

    assert report["integrity_check"] == "ok"
    assert report["wal_applied"] is True
    assert report["tables"]["orders"] == order_count == 6
    assert report["tables"]["audit_log"] == audit_count == 7
    digest = hashlib.sha256(DB_PATH.read_bytes()).hexdigest()
    assert report["recovered_db_sha256"] == digest
    assert re.fullmatch(r"[0-9a-f]{64}", report["recovered_db_sha256"])


def _run_as_script():
    tests = [
        test_output_files_exist,
        test_recovered_database_is_clean_and_integral,
        test_committed_wal_rows_and_update_are_recovered,
        test_audit_log_contains_post_backup_events,
        test_report_matches_recovered_database,
    ]
    results = []
    failed = 0
    for test in tests:
        try:
            test()
            results.append({"name": test.__name__, "status": "passed"})
        except Exception as exc:
            failed += 1
            results.append(
                {
                    "name": test.__name__,
                    "status": "failed",
                    "message": str(exc),
                    "trace": traceback.format_exc(),
                }
            )

    ctrf_path = Path(os.environ.get("CTRF_PATH", "/logs/verifier/ctrf.json"))
    ctrf_path.parent.mkdir(parents=True, exist_ok=True)
    ctrf = {
        "results": {
            "tool": {"name": "custom-python-assertions"},
            "summary": {
                "tests": len(results),
                "passed": len(results) - failed,
                "failed": failed,
            },
            "tests": results,
        }
    }
    ctrf_path.write_text(json.dumps(ctrf, indent=2) + "\n", encoding="utf-8")
    if failed:
        raise SystemExit(1)


if __name__ == "__main__":
    _run_as_script()
