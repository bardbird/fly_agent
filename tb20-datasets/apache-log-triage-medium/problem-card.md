# Problem Card

## Scenario
A web operations team needs daily endpoint and client-error reports from current and rotated Apache logs.

## Difficulty
Medium.

## Required skill
Parsing quoted log fields, reading gzip files, query-string normalization, CSV generation, JSON generation.

## Observable artifacts
- `/app/reports/traffic_summary.csv`
- `/app/reports/error_clients.json`

## Non-goals
The task does not require running Apache or configuring log rotation.
