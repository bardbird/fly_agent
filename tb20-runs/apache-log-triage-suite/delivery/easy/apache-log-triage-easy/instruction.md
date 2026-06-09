# Apache Log Triage: Common Log Summary

## Context
You are given a small Apache HTTP Server access log in Common Log Format. Produce a machine-readable summary that can be consumed by a monitoring pipeline.

## Files Available
- `/app/data/access.log`: input access log.
- `/app/report.json`: output file you must create.

## Task
Parse `/app/data/access.log` and write `/app/report.json`.

## Input Format
Each non-empty line uses Apache Common Log Format:

```text
host ident user [day/month/year:hour:minute:second zone] "METHOD path HTTP/version" status bytes
```

The byte field is either a non-negative integer or `-`. Treat `-` as `0`.

## Required Output
Write a UTF-8 JSON object to `/app/report.json` followed by a trailing newline. The object must have exactly these keys:

```json
{
  "total_requests": 0,
  "unique_clients": 0,
  "status_counts": {},
  "top_paths": [],
  "largest_response_bytes": 0
}
```

`status_counts` maps status codes as strings to integer counts.

`top_paths` is an array of at most three objects with keys `path` and `count`.

## Behavioral Requirements
- Count every non-empty log line.
- Count unique clients by the `host` field.
- Use the request path exactly as it appears inside the quoted request line.
- Sort `top_paths` by descending count, then ascending path.
- Sort JSON object keys alphabetically inside `status_counts`.
- Use JSON numbers for all counts and byte values.

## Edge Cases
- A byte field of `-` counts as `0`.
- If fewer than three distinct paths exist, output only the available paths.
- Do not include timestamps or HTTP methods in the output.

## Constraints
- Do not modify `/app/data/access.log`.
- Do not require network access.
- The solution may use shell, Python, or other tools already present in the container.

## Examples
For two requests to `/index.html` and one request to `/health`, `top_paths` should list `/index.html` before `/health`.

## Success Criteria
The verifier will check that `/app/report.json` exists, is valid JSON, has the required schema, has a trailing newline, and contains the expected counts for the supplied log.
