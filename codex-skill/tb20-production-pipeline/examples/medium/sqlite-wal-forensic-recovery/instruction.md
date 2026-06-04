# SQLite WAL Forensic Recovery

An incident response team copied an SQLite database backup and later found a
separate WAL file recovered from a crashed workstation. The main database file
is readable, but it is missing committed changes that still live in the WAL.

Recover the committed data and produce a clean SQLite database export.

## Available files

- `/app/backup/app.db` - the copied main SQLite database file.
- `/app/recovered_wal/app.db-wal` - the recovered WAL file for that database.
- `/app/schema_notes.md` - notes about the expected tables.

The WAL file must be paired with the main database using the same base filename
before SQLite can apply it. Do not edit the input files in place.

## Required output

Create these files:

- `/app/output/recovered.db`
- `/app/output/recovery_report.json`

`recovered.db` must be a clean SQLite database that contains the committed data
from both the main database and the WAL. It should be usable on its own without
requiring a sibling `-wal` or `-shm` file.

`recovery_report.json` must be valid JSON with at least these fields:

```json
{
  "integrity_check": "ok",
  "wal_applied": true,
  "tables": {
    "orders": 0,
    "audit_log": 0
  },
  "recovered_db_sha256": "..."
}
```

The row counts in `tables` must describe the recovered database, not the stale
main database.
