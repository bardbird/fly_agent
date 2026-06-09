# Test Generation Brief

Observable outputs:
- `/app/report.json`

Fixture plan:
- Provide a small Common Log Format file with repeated paths, multiple clients, status variety, and a `-` byte field.

Normal behavior tests:
- Total request count.
- Unique client count.
- Status-code counts.
- Top path ordering by count then path.
- Largest response byte value.

Boundary tests:
- Byte field `-` becomes zero.
- Fewer than three unique paths should shorten `top_paths`.

Adversarial/wrong-solution tests:
- Reject solutions that count methods instead of paths.
- Reject solutions that sort paths lexicographically before count.
- Reject solutions that emit invalid JSON or omit the trailing newline.

Old behavior preservation tests:
- Not applicable.

Hidden-test strategy:
- Use a similar Common Log Format file with different paths and ties.

Wrong implementations to reject:
- Grepping only status `200`.
- Assuming every byte field is numeric.
- Counting duplicate clients as unique clients.
