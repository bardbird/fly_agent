# Test Generation Brief

Observable outputs:
- `/app/reports/traffic_summary.csv`
- `/app/reports/error_clients.json`

Fixture plan:
- Provide current and rotated gzip Apache combined logs.
- Include query strings, byte field `-`, user agents with spaces, 4xx and 5xx statuses, and repeated clients.

Normal behavior tests:
- Gzip file is read.
- Query strings are removed before path aggregation.
- CSV rows are sorted by count then path.
- Byte totals treat `-` as zero.
- Error-client thresholds are applied.

Boundary tests:
- Paths tied by total request count sort lexicographically.
- Clients with only one 5xx are not anomalous.

Adversarial/wrong-solution tests:
- Reject solutions that ignore rotated logs.
- Reject solutions that split quoted user-agent strings incorrectly.
- Reject solutions that aggregate `/products?id=10` separately from `/products?id=11`.

Old behavior preservation tests:
- Not applicable.

Hidden-test strategy:
- Change file order and add additional query-string variants while preserving the same schema.

Wrong implementations to reject:
- Reading only `/app/logs/access.log`.
- Counting all 3xx as errors.
- Emitting CSV columns in a different order.
