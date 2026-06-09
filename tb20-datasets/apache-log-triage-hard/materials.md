# Materials

Source: Apache HTTP Server documentation, "Log Files".

Relevant behavior:
- Apache supports configurable access-log formats including Common and Combined forms.
- Combined logs add quoted referer and user-agent fields.
- Operational log analysis often spans rotated files and compressed archives.

Task adaptation:
- The task combines Common and Combined formats across multiple files.
- The output is an incident-oriented JSON report that requires parsing, filtering, aggregation, sorting, and timestamp bucketing.
