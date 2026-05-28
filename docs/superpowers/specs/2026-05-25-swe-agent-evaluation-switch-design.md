# SWE-Agent Evaluation Switch Design

## Goal

Replace the current hand-written model patch generation and evaluation loop with SWE-agent, while keeping the existing SWE-Pro pipeline shape intact.

## Scope

The change is limited to the model evaluation stage. Candidate discovery, candidate registration, task creation, task package initialization, package QC, acceptance report generation, and run-stage persistence stay on the current path.

The production path should no longer call the custom JSON-edits agent loop in `eval_models.py`. SWE-agent becomes the only model patch generation runner used by `SwePipelineService`.

## Design

Add a Python adapter script under `tools/swe-pro-production/scripts`, named `eval_with_swe_agent.py`. The adapter is the boundary between the existing task package format and SWE-agent. Java should not depend on SWE-agent internal output details.

The adapter accepts the same package-level inputs the current evaluator receives: package directory, model name, base URL, API key env var, attempt count, output name, provider settings, max tokens, timeout, and agent step limit. It reads `task.json`, `problem_statement.md`, and the checked-out `repo/` directory. It must not include `gold.patch`, `test.patch`, `FAIL_TO_PASS`, `PASS_TO_PASS`, or selected test metadata in the prompt given to the model.

For each attempt, the adapter runs SWE-agent against the base repository and problem statement. It captures SWE-agent trajectory and prediction artifacts, extracts the candidate patch, writes it as `model_evaluation/<out-name>/run_XX/model.patch`, and then validates that patch using the existing package oracle: apply `model.patch`, apply `patches/test.patch`, run the first `fail_to_pass` command, reset, reapply `model.patch`, and run the first `pass_to_pass` command.

The adapter writes a `summary.json` compatible with the current pipeline and QC code:

- `model`
- `base_url`
- `requested_out_name`
- `output_dir`
- `attempts`
- `passes`
- `pass_rate`
- `pass_nonzero`
- `pass_rate_lte_50_percent`
- `results`

Each run result should continue to include patch application status, test patch application status, fail-to-pass status, pass-to-pass status, changed files, pass/fail, and error text if present. The adapter also syncs `task.json.metadata.model_evaluation` in the same shape currently consumed by the acceptance report.

`SwePipelineService.runModelEvaluation` should call `eval_with_swe_agent.py` directly. The existing Qwen and Opus stage semantics remain:

- Qwen runs pass@4 and passes the difficulty gate only when pass rate is at most 50%.
- Opus runs pass@8 and passes the gate when at least one attempt passes.

Do not add a `legacy` versus `swe-agent` production switch. The old evaluator may remain in the repository only if tests or local tooling still need helper functions during migration, but no production command path should invoke it.

## Configuration

Add only SWE-agent-specific runtime configuration needed by the adapter, such as:

- `swe.swe-agent.root`
- `swe.swe-agent.python`
- `swe.swe-agent.max-steps`

Existing model configuration for Qwen and Opus remains the source of model name, provider, base URL, token env var, max tokens, temperature, timeout, and thinking settings.

The environment check stage should validate that the adapter script exists and that the configured SWE-agent root is present. If SWE-agent is vendored as a submodule or external checkout, missing files should fail early with a clear error message.

## Error Handling

Adapter failures should be surfaced as stage failures with the run log recorded. Individual model attempts should produce per-run artifacts even when SWE-agent exits non-zero, returns no patch, or produces a patch that does not apply.

Infrastructure failures detected during oracle execution should continue to fail the evaluation rather than being counted as model failures.

## Tests

Focused tests should verify:

- `SwePipelineService` invokes `eval_with_swe_agent.py` instead of `eval_models.py`.
- Qwen and Opus commands still pass the expected attempts, output names, provider fields, max tokens, timeout, and agent max steps.
- Production code no longer references the JSON-edits evaluator as the model evaluation command.
- The adapter can convert a synthetic SWE-agent patch artifact into `model.patch` and `summary.json`.
- The adapter rejects or sanitizes prompts that would leak `gold.patch`, `test.patch`, `fail_to_pass`, or `pass_to_pass`.

Verification commands:

- `mvn -pl fly-agent-service -am test`
- Python unit tests for the SWE-agent adapter under `tools/swe-pro-production/scripts`
- `mvn -pl fly-agent-server -am -DskipTests package`
