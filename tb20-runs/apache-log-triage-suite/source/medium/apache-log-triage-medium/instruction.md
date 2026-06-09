# Apache Log Triage: Rotated Combined Logs

## Context
An operations team keeps the current Apache access log as plain text and the previous log as gzip. You need to produce stable reports that summarize traffic by endpoint and identify clients with repeated errors.

## Files Available
- `/app/logs/access.log`: current Apache combined access log.
- `/app/logs/access.log.1.gz`: previous Apache combined access log, gzip-compressed.
- `/app/reports/traffic_summary.csv`: CSV output you must create.
- `/app/reports/error_clients.json`: JSON output you must create.

## Task
Read both log files and create the two output files under `/app/reports`.

## Input Format
Each non-empty line uses Apache Combined Log Format:

```text
host ident user [day/month/year:hour:minute:second zone] "METHOD request-target HTTP/version" status bytes "referer" "user-agent"
```

The byte field is either a non-negative integer or `-`. Treat `-` as `0`.

## Required Output
Create `/app/reports/traffic_summary.csv` with this exact header:

```text
path,total_requests,error_requests,total_bytes
```

Each following row summarizes one normalized path:
- `path`: request target path without the query string.
- `total_requests`: count of all requests for the path.
- `error_requests`: count of status codes from 400 through 599 for the path.
- `total_bytes`: sum of byte fields for the path, treating `-` as `0`.

Sort rows by descending `total_requests`, then ascending `path`.

Create `/app/reports/error_clients.json` as a UTF-8 JSON object followed by a trailing newline:

```json
{
  "anomalous_clients": []
}
```

`anomalous_clients` must contain clients with at least three 4xx requests or at least two 5xx requests. Each client object must have:
- `host`
- `total_requests`
- `client_error_requests`
- `server_error_requests`

Sort anomalous clients by ascending `host`.

## Behavioral Requirements
- Read both the plain log and the gzip log.
- Correctly parse quoted referer and user-agent fields.
- Remove only the query string when normalizing paths.
- Treat 4xx as client errors and 5xx as server errors.
- Create `/app/reports` if it does not exist.

## Edge Cases
- Request targets may contain query strings.
- Byte fields may be `-`.
- User-agent strings may contain spaces.

## Constraints
- Do not modify files in `/app/logs`.
- Do not require network access.
- Use only tools available in the container.

## Examples
`GET /search?q=boots HTTP/1.1` and `GET /search?q=coats HTTP/1.1` both aggregate under `/search`.

## Success Criteria
The verifier will check file existence, CSV header and ordering, JSON schema, trailing newline, gzip handling, query-string normalization, byte totals, and error-client thresholds.
