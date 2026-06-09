
import csv
import json
from pathlib import Path

index_raw = Path("/app/audit/zone_index.json").read_text()
assert index_raw.endswith("\n")
index = json.loads(index_raw)
assert index["valid_zones"] == ["America/Metro", "US/MetroAlias"]
assert index["invalid_zones"] == [{"path": "Etc/Broken", "reason": "truncated TZif data block"}]
assert index["duplicate_groups"] == [["America/Metro", "US/MetroAlias"]]
assert index["version_counts"] == {"2": 2}

report = Path("/app/audit/local_time_report.csv")
assert report.read_text().endswith("\n")
rows = list(csv.DictReader(report.open(newline="")))
assert rows == [
    {"id": "e1", "zone": "America/Metro", "local_time": "2025-03-09T01:30:00", "classification": "valid", "candidate_utc": "2025-03-09T06:30:00Z", "offsets": "-18000"},
    {"id": "e2", "zone": "America/Metro", "local_time": "2025-03-09T02:30:00", "classification": "nonexistent", "candidate_utc": "", "offsets": ""},
    {"id": "e3", "zone": "America/Metro", "local_time": "2025-03-09T03:30:00", "classification": "valid", "candidate_utc": "2025-03-09T07:30:00Z", "offsets": "-14400"},
    {"id": "e4", "zone": "America/Metro", "local_time": "2025-11-02T01:30:00", "classification": "ambiguous", "candidate_utc": "2025-11-02T05:30:00Z|2025-11-02T06:30:00Z", "offsets": "-14400|-18000"},
    {"id": "e5", "zone": "US/MetroAlias", "local_time": "2025-11-02T01:30:00", "classification": "ambiguous", "candidate_utc": "2025-11-02T05:30:00Z|2025-11-02T06:30:00Z", "offsets": "-14400|-18000"},
]
