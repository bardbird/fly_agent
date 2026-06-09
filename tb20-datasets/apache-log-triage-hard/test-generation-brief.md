# Test Generation Brief

Observable outputs:
- `/app/incident_report.json`

Fixture plan:
- Provide three log files: current combined log, rotated common log, and older gzip combined log.
- Include malformed lines, query strings, byte field `-`, 2xx/3xx/4xx/5xx statuses, repeated clients, and events spanning three five-minute windows.

Normal behavior tests:
- Valid parsed line count.
- Malformed skipped line count.
- Status-family counts.
- Top error path ordering.
- Client incident threshold and ordering.
- Five-minute window bucketing and ordering.

Boundary tests:
- Common and Combined formats in the same task.
- `304 -` byte field counts as zero and is not an error.
- Tied client incident counts sort by request count then host.

Adversarial/wrong-solution tests:
- Reject solutions that fail on malformed lines.
- Reject solutions that read only plain text logs.
- Reject solutions that treat 3xx statuses as errors.
- Reject solutions that bucket by wall-clock minute instead of five-minute floor.

Old behavior preservation tests:
- Not applicable.

Hidden-test strategy:
- Vary malformed-line positions, add path query variants, and place threshold clients in different files.

Wrong implementations to reject:
- Ignoring `/app/logs/access.log.2.gz`.
- Aggregating `/api/items?page=1` and `/api/items?page=2` as separate paths.
- Sorting `top_error_paths` alphabetically before count.
