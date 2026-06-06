# Stage Gate Reference

Use this when a task fails validation or a stage status is ambiguous.

Allowed statuses:

- `PASS`: real evidence file exists and gate condition is met.
- `FAIL`: real evidence shows gate condition failed.
- `BLOCKED`: missing required input or unclear task state.
- `BLOCKED_BY_DEPENDENCY`: dependency such as Docker, Harbor, model runner, or Python is missing.
- `SKIPPED`: only allowed when the user explicitly excludes a stage.

High-risk gates:

- Oracle run cannot pass without real command output.
- Verifier cannot pass without real test output.
- Agent rollout cannot pass without real trajectory/run evidence.
- Delivery cannot pass without `delivery_manifest.json`.

If unsure, use `BLOCKED`, not `PASS`.
