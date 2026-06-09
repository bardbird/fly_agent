# Materials

Source: Apache HTTP Server documentation, "Log Files".

Relevant behavior:
- Combined Log Format extends Common Log Format with quoted referer and user-agent fields.
- Access logs frequently rotate, so operational analysis may need to read multiple files.
- Status code classes identify client-side and server-side failures.

Task adaptation:
- The task provides one plain log and one gzip-compressed rotated log.
- The expected outputs are a sorted CSV traffic summary and a JSON error-client report.
