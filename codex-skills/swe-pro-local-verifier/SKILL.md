---
name: swe-pro-local-verifier
description: Use this Codex skill when turning a SWE-Pro GitHub PR or local task package into a locally verified SWE-Pro package before model evaluation. It guides AI-assisted correction of gold.patch, test.patch, task.json, selected test commands, runtime_env, and Dockerfile until patch application and Docker baseline/fixed/pass-to-pass verification pass, then hands the package back to the existing fly-agent SWE-Pro pipeline resume flow.
---

# SWE-Pro Local Verifier

This is a Codex-side skill. Do not place it under the product `skills/` tree and do not treat it as a fly-agent runtime skill.

## Goal

Drive one SWE-Pro candidate from PR/package evidence to `Local verification passed`.

The AI loop owns the fragile part:

- package initialization and repo checkout
- `gold.patch` and `test.patch` review/correction
- selected test command correction
- runtime/Dockerfile correction
- Docker baseline/fixed/pass-to-pass verification

The fly-agent backend owns the deterministic tail after local verification:

- model evaluation
- Docker image export
- QC review
- package export

## Core Rule

Do not start model evaluation until local Docker verification proves:

- `baseline`: applies `test.patch` only and fails
- `fixed`: applies `gold.patch` plus `test.patch` and passes
- `pass-to-pass`: applies `gold.patch` only and passes preserved tests

Use the repository scripts when available:

```bash
python3 tools/swe-pro-production/scripts/package_task.py <package_dir> --docker
```

## Workflow

1. Claim exactly one candidate/package.
   - Use a DB/API lease in production orchestration.
   - Use a package-local lock before editing files.
   - Never let two agents edit the same package directory.

2. Build or inspect the task package.
   - Required files: `task.json`, `problem_statement.md`, `patches/gold.patch`, `patches/test.patch`, `scripts/run_selected_tests.sh`, `scripts/verify_patch_application.sh`, `dockerfiles/Dockerfile`.
   - Keep all AI edits inside the package directory unless a reusable toolkit bug is proven.

3. Review oracle quality before chasing environment failures.
   - Read `problem_statement.md`, PR evidence, `gold.patch`, and `test.patch`.
   - Reject or rewrite `test.patch` if it tests private implementation details, unrelated behavior, new helper names from gold, snapshots unrelated to the issue, time/network/randomness, or brittle formatting absent from the issue.
   - Run an alternate-implementation probe: ask whether a semantically correct solution with different helper names, config field names, file layout, or validation order would fail to compile or fail for reasons not stated in the issue. If yes, rewrite toward public behavior.
   - For new integrations/providers, prefer testing the behavior visible at the provider/factory boundary. Do not require gold-only struct fields such as `Domain` when names like `Hostname` or `CustomDomain` would also satisfy the issue.
   - Read `references/oracle.md` when deciding whether to rewrite tests.

4. Verify patch mechanics.
   - Run `bash scripts/verify_patch_application.sh`.
   - If patch application fails, fix patch paths, repo baseline, line endings, generated files, or patch split.
   - Check newly authored patch hunk headers after manual edits. For new files, the `+1,N` count must match the number of added lines; a wrong count can truncate the generated file even when the patch appears readable.
   - Do not continue to Docker while patches cannot apply cleanly.

5. Fix selected tests and runtime.
   - Run local modes when cheap: `bash scripts/run_selected_tests.sh baseline`, `fixed`, and `pass-to-pass`.
   - Correct `fail_to_pass`, `pass_to_pass`, `selected_test_files_to_run`, and `before_repo_set_cmd` in `task.json`.
   - Then update `scripts/run_selected_tests.sh` consistently.
   - For language-specific setup, read `references/language-playbooks.md`.

6. Fix Dockerfile with tight scope.
   - Prefer changes in `dockerfiles/Dockerfile` and `runtime_env.json`.
   - Keep install commands deterministic and cache-conscious.
   - Use `references/docker.md` for image size and cache cleanup rules.

7. Run Docker verification until it passes or the candidate is rejected.
   - Use `scripts/local_verify_loop.py` for a deterministic wrapper.
   - Inspect `logs/docker_build.log`, `logs/docker/baseline.log`, `logs/docker/fixed.log`, `logs/docker/pass_to_pass.log`, and `logs/docker/validation.json`.
   - Treat Docker-only patch failures as first-class patch/runtime issues. The validation image may not contain `.git` metadata or repository attributes, so CRLF-sensitive hunks can apply locally but fail in Docker. See `references/docker.md` before changing tests.
   - Inspect the generated Docker context exclusions when a package compiles locally but not in Docker. `package_task.py` may rewrite `.dockerignore`; broad patterns can exclude real source directories such as Go packages named `env`.
   - A passing `logs/docker/validation.json` with `"ok": true` is the local verification gate.

8. Run eval-shell parity checks before handoff.
   - Model evaluation executes task commands through `bash -lc` in a validation image. Re-run at least `before_repo_set_cmd` and command-tool discovery with the same shell form.
   - For each selected command, verify the language tool resolves under login-shell semantics, for example `bash -lc 'command -v go && go version'`.
   - If a command needs a toolchain path, encode it in `task.json` commands and `scripts/run_selected_tests.sh`, for example `export PATH=/usr/local/go/bin:/go/bin:$PATH && go test ...`.
   - Treat `command not found` here as infrastructure failure even if Docker baseline/fixed/pass-to-pass already passed.

9. Write handoff evidence.
   - Update `verification.md` with the final commands and results.
   - Write `.swe-ai/handoff.json` using `references/handoff.md`.
   - Run `scripts/docker_budget.py <package_dir>` and include the result.

10. Resume fly-agent only after local verification passes.
   - Read `references/api-calls.md` before calling backend APIs.
   - The local loop should use zero backend calls after task claim.
   - The normal handoff uses one to three backend calls per package, excluding polling.

## Stop Conditions

Reject the candidate instead of overfitting when:

- issue evidence does not support the hidden test behavior
- `gold.patch` is not a plausible user-visible fix
- baseline cannot fail without implementation-specific assertions
- fixed passes only by testing reference internals
- Docker requires broad system/toolkit changes unique to the candidate
- image size remains above budget after reasonable cleanup

## Files That May Be Edited

Default allowed package files:

- `problem_statement.md`
- `task.json`
- `verification.md`
- `patches/gold.patch`
- `patches/test.patch`
- `scripts/run_selected_tests.sh`
- `scripts/verify_patch_application.sh`
- `dockerfiles/Dockerfile`
- `runtime_env.json`
- `review/*.md` only for final evidence, not for making tests pass

Avoid editing fly-agent Java/Python toolkit code during candidate work. If a systemic fix is required, stop package work, document the blocker, and handle the toolkit change as a separate engineering task.
