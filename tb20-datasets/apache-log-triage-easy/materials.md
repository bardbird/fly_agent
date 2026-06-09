# Materials

Source: Apache HTTP Server documentation, "Log Files".

Relevant behavior:
- Apache access logs commonly record client host, timestamp, quoted request line, status code, and byte count.
- Common Log Format uses fields equivalent to `%h %l %u %t \"%r\" %>s %b`.
- A byte field may be represented as `-` when no bytes are sent.

Task adaptation:
- The task asks the agent to parse a small Common Log Format fixture.
- The expected output is a deterministic JSON summary suitable for automated verification.
