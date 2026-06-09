# Apache Log Triage: Incident Report

## Context
A production service generated several Apache access logs during an incident. Some files are rotated, one file is gzip-compressed, and a few lines are malformed. Produce a deterministic incident report without changing the original logs.

## Files Available
- `/app/logs/access.log`: current access log, Apache Combined Log Format.
- `/app/logs/access.log.1`: rotated access log, Apache Common Log Format.
- `/app/logs/access.log.2.gz`: older rotated access log, gzip-compressed Apache Combined Log Format.
- `/app/incident_report.json`: output file you must create.

## Task
Read every file matching `/app/logs/access.log*`, parse valid log lines, skip malformed lines, and write `/app/incident_report.json`.

## Input Format
Valid lines use either Apache Common Log Format:

```text
host ident user [day/month/year:hour:minute:second zone] "METHOD request-target HTTP/version" status bytes
```

or Apache Combined Log Format:

```text
host ident user [day/month/year:hour:minute:second zone] "METHOD request-target HTTP/version" status bytes "referer" "user-agent"
```

The byte field is either a non-negative integer or `-`. Treat `-` as `0`. Malformed lines must be skipped and counted.

## Required Output
Write a UTF-8 JSON object to `/app/incident_report.json` followed by a trailing newline. The object must have exactly these top-level keys:

```json
{
  "client_incidents": [],
  "parsed_lines": 0,
  "skipped_lines": 0,
  "status_families": {},
  "top_error_paths": [],
  "five_minute_error_windows": []
}
```

`status_families` must contain keys `2xx`, `3xx`, `4xx`, and `5xx`.

`top_error_paths` contains up to five paths with at least one 4xx or 5xx response. Each item has:
- `path`
- `error_requests`

Sort by descending `error_requests`, then ascending `path`.

`client_incidents` contains clients with at least three total errors or at least two 5xx responses. Each item has:
- `host`
- `requests`
- `errors`
- `server_errors`
- `total_bytes`

Sort by descending `errors`, then descending `requests`, then ascending `host`.

`five_minute_error_windows` contains five-minute UTC time buckets with at least two errors. Each item has:
- `window_start`: ISO 8601 timestamp with timezone offset, rounded down to a five-minute boundary.
- `request_count`
- `error_count`

Sort windows by ascending `window_start`.

## Behavioral Requirements
- Read plain text and gzip files.
- Support both Common and Combined log formats.
- Skip malformed lines instead of failing.
- Normalize request paths by removing query strings.
- Treat status codes 400 through 599 as errors.
- Treat status codes 500 through 599 as server errors.
- Round each timestamp down to the start of its five-minute bucket.
- Use JSON numbers for counts and byte totals.

## Edge Cases
- Rotated files may have different log formats.
- Byte fields may be `-`.
- User-agent strings may contain spaces.
- Query strings must not affect path aggregation.
- Malformed lines must not contribute to any other count.

## Constraints
- Do not modify files in `/app/logs`.
- Do not require network access.
- Use only tools available in the container.

## Examples
`10/Oct/2025:14:07:30 +0000` belongs to bucket `2025-10-10T14:05:00+00:00`.

## Success Criteria
The verifier will check that the report exists, is valid JSON, ends with a newline, has the required schema, correctly handles gzip, skips bad rows, buckets timestamps, normalizes paths, and applies all sorting and incident thresholds.
