# Problem Card

## Scenario
A production service had a short incident with login failures and backend errors. The agent must summarize the incident from mixed Apache access logs.

## Difficulty
Hard.

## Required skill
Robust log parsing, gzip handling, malformed-row handling, timestamp parsing, five-minute bucketing, path normalization, multi-key sorting, deterministic JSON output.

## Observable artifact
`/app/incident_report.json`.

## Non-goals
The task does not require running Apache, making HTTP requests, or changing server configuration.
