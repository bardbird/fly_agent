FROM python:3.13-slim

RUN apt-get update \
  && apt-get install -y --no-install-recommends bash sqlite3 \
  && rm -rf /var/lib/apt/lists/*

WORKDIR /app

RUN mkdir -p /app/backup /app/recovered_wal /app/output \
  && python3 - <<'PY'
import shutil
import sqlite3
from pathlib import Path

root = Path("/tmp/wal-fixture")
if root.exists():
    shutil.rmtree(root)
root.mkdir(parents=True)
db = root / "app.db"

conn = sqlite3.connect(db)
conn.execute("PRAGMA journal_mode=WAL")
conn.execute("PRAGMA synchronous=FULL")
conn.execute("""
CREATE TABLE orders (
    id INTEGER PRIMARY KEY,
    customer TEXT NOT NULL,
    amount_cents INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL
)
""")
conn.execute("""
CREATE TABLE audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    entity_id INTEGER NOT NULL,
    detail TEXT NOT NULL,
    created_at TEXT NOT NULL
)
""")
base_orders = [
    (1001, "Ada Lovelace", 12450, "paid", "2026-05-21T09:15:00Z"),
    (1002, "Ben Nassar", 8799, "pending", "2026-05-21T10:02:00Z"),
    (1003, "Cai Ren", 500, "cancelled", "2026-05-21T10:45:00Z"),
]
conn.executemany("INSERT INTO orders VALUES (?, ?, ?, ?, ?)", base_orders)
for order_id in (1001, 1002, 1003):
    conn.execute(
        "INSERT INTO audit_log(event_type, entity_id, detail, created_at) VALUES (?, ?, ?, ?)",
        ("order_created", order_id, "baseline backup record", "2026-05-21T11:00:00Z"),
    )
conn.commit()
conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
shutil.copyfile(db, "/app/backup/app.db")

wal_orders = [
    (1004, "Dana Ortiz", 21990, "paid", "2026-05-21T12:10:00Z"),
    (1005, "Eli Park", 4310, "review", "2026-05-21T12:40:00Z"),
    (1006, "Fay Zhou", 15200, "paid", "2026-05-21T13:05:00Z"),
]
conn.executemany("INSERT INTO orders VALUES (?, ?, ?, ?, ?)", wal_orders)
conn.execute("UPDATE orders SET status = 'shipped' WHERE id = 1002")
for order_id in (1004, 1005, 1006):
    conn.execute(
        "INSERT INTO audit_log(event_type, entity_id, detail, created_at) VALUES (?, ?, ?, ?)",
        ("order_created", order_id, "post-backup WAL record", "2026-05-21T13:10:00Z"),
    )
conn.execute(
    "INSERT INTO audit_log(event_type, entity_id, detail, created_at) VALUES (?, ?, ?, ?)",
    ("status_changed", 1002, "pending->shipped from recovered WAL", "2026-05-21T13:15:00Z"),
)
conn.commit()

wal = db.with_suffix(".db-wal")
if not wal.exists():
    raise RuntimeError("WAL file was not created")
shutil.copyfile(wal, "/app/recovered_wal/app.db-wal")
conn.close()
PY

RUN cat > /app/schema_notes.md <<'NOTE'
Expected tables:
- orders(id, customer, amount_cents, status, created_at)
- audit_log(id, event_type, entity_id, detail, created_at)

The backup was taken before a later committed WAL transaction. A complete
recovery should include post-backup order rows and the status update for order
1002.
NOTE
