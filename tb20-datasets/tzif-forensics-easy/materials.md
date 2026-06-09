# Materials

Source basis:
- RFC 8536 describes the TZif binary format used by compiled zoneinfo files.
- IANA tzdb is the standard source ecosystem for civil time-zone data.

Relevant behavior:
- TZif headers contain six big-endian count fields.
- Version 2 and 3 files contain a compatibility 32-bit block followed by a second 64-bit block.
- Transition records map UTC transition instants to local-time types.
- Local clock transitions can create nonexistent local times (gaps) and ambiguous local times (folds).

Task adaptation:
- All fixtures are synthetic and intentionally small.
- The tasks require implementing format-aware parsers and deterministic reports rather than using system zoneinfo APIs.
