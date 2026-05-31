# System API Calls

The AI local verification loop should not depend on fly-agent APIs. It should work from local files, git, Docker, and GitHub evidence.

## Expected Calls Per Candidate

### Local verification only

Expected backend calls: `0`.

The agent may read candidate metadata from an already exported CSV, direct DB query, or user-provided PR URL. Once a package directory exists, all correction and validation should be local.

### Normal handoff after local verification

Expected backend calls, excluding status polling: `1-3`.

1. Optional task creation: `POST /api/v1/swe/tasks/from-candidate` or `POST /api/v1/swe/tasks`
   - Needed only if no task exists yet.
2. Start or resume pipeline: `POST /api/v1/swe/runs/start`
   - Use `resumeRunId` and `resumeFromStage` when a run record already exists.
   - When resuming from a locally verified package, include `samplePath` even for GitHub PR tasks. Otherwise the backend may run the original candidate path instead of the corrected package.
   - `samplePath` must be visible from the fly-agent backend containers. In the
     local Docker deployment, use `/data/fly-agent/swe-output/...`, not a repo
     checkout path such as `/home/ubuntu/gitee/fly_agent/swe-output/...`.
   - Preferred resume stage after local verification is `MODEL_OPUS_EVAL`.
3. Optional status read: `GET /api/v1/swe/runs/detail?runId=<id>`
   - Use once to verify the backend accepted the handoff.

### Polling budget

Polling is operational, not part of the core dependency.

- Recommended interval: 30-60 seconds.
- Expected calls for a one-hour model stage: 60-120 `GET /runs/detail` calls if polling continuously.
- Prefer sparse polling or event-driven monitoring for batches.

## Batch Formula

For `N` candidates:

```text
backend_calls = discovery_pages + N * (task_create_optional + handoff_start_or_resume + handoff_confirm_optional + polling)
```

Typical values:

- Candidate list already exported: `discovery_pages = 0`
- One page of candidates/allowed repos: `discovery_pages = 1-2`
- Existing task and run: `N * 1` handoff call
- Existing task but no run: `N * 1-2` handoff calls, depending on backend behavior
- No task: `N * 2-3` calls

## Current Backend Compatibility Notes

The current DTO supports `resumeRunId` and `resumeFromStage`.

The XXL job JSON parser should also pass `resumeFromStage` if XXL is used for handoff. If handoff uses HTTP directly, the current controller DTO path is enough.

If the backend is running an existing package with `samplePath`, confirm that the selected code path executes the deterministic tail instead of merely inspecting existing model summaries. If it only inspects summaries, add a small backend fix before relying on package-mode handoff.

## API Payload Examples

Create a task from an existing local package:

```json
{
  "taskName": "production-task-owner-repo-123",
  "samplePath": "/abs/path/to/package"
}
```

Create from candidate:

```json
{
  "candidateId": 123,
  "taskName": "production-task-owner-repo-123"
}
```

Resume after local verification:

```json
{
  "taskId": 18,
  "resumeRunId": 38,
  "resumeFromStage": "MODEL_OPUS_EVAL",
  "samplePath": "/data/fly-agent/swe-output/production-task-example-123"
}
```
