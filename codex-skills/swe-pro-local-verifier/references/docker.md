# Docker And Runtime Rules

Docker verification is the local gate before model evaluation.

## Required Command

```bash
python3 tools/swe-pro-production/scripts/package_task.py <package_dir> --docker
```

Passing output must leave:

- `logs/docker_build.log`
- `logs/docker/baseline.log`
- `logs/docker/fixed.log`
- `logs/docker/pass_to_pass.log`
- `logs/docker/validation.json` with `"ok": true`

## Image Size Budget

Default soft budget: 8 GiB.

Default hard budget: 12 GiB.

If the image exceeds the soft budget:

- remove package manager caches in the same `RUN` layer
- avoid copying `.git`, `node_modules`, build outputs, and local virtualenvs
- prefer `--no-install-recommends` for apt
- use `PIP_NO_CACHE_DIR=1`
- clean npm/pnpm/yarn caches when safe
- avoid installing full desktop/browser stacks unless tests require them

If the image exceeds the hard budget, reject or escalate unless a project-specific reason is documented.

## Cache Cleanup Safety

Allowed cleanup:

- package-local generated build outputs before Docker context creation
- images tagged for the current task
- BuildKit cache records associated with the current task label/tag, if labels exist

Avoid:

- `docker system prune -a`
- deleting shared named volumes
- deleting unrelated images used by other running tasks
- cleaning global Docker cache during a concurrent batch

## Common Fixes

Patch application inside Docker:

- Do not assume the validation image contains `.git`, `.gitattributes`, or working-tree attributes. Docker contexts commonly exclude VCS metadata.
- If `git apply` succeeds in the package repo but fails inside Docker on UI/JSON/text hunks, check for CRLF/LF differences in the target files and patches.
- Prefer fixing the patch line endings or normalizing the affected files in `gold.patch` / `test.patch`.
- If the patch content is semantically correct and only whitespace/line-ending context differs in Docker, it is acceptable to use `git apply --ignore-space-change --ignore-whitespace` in `scripts/run_selected_tests.sh`, but use it consistently for baseline, fixed, and pass-to-pass modes and document why in `verification.md`.
- After manually editing a patch, verify new-file hunk counts. For example, `@@ -0,0 +1,102 @@` must match 102 added lines. A stale count can silently truncate the applied file and surface later as an EOF or syntax error.

Docker context exclusions:

- Re-check `.dockerignore` after running `package_task.py`; the packager may rewrite it.
- Broad directory patterns such as `repo/**/env` can exclude real source packages, for example Go code under `pkg/.../env`.
- If a source directory is missing only inside Docker, compare `find repo/...` on the host with `docker run ... ls /workspace/repo/...`.
- A broad generated ignore pattern is a toolkit issue. Prefer a toolkit fix for repeated cases; package-level workarounds are acceptable only when they do not weaken oracle quality or hide a real source dependency.

Python:

- install project in editable mode only if tests need imports
- install explicit test dependencies from `pyproject.toml`, `requirements*.txt`, or project docs
- export `PYTHONPATH=/workspace/repo` only when project layout requires it

Node/TypeScript:

- run package manager in the nearest directory with `package.json`
- prefer lockfile-respecting installs
- add `corepack enable` when using pnpm/yarn
- avoid copying host `node_modules`

Java/Kotlin:

- prefer project wrapper commands when available
- keep Maven/Gradle caches out of final delivery artifacts
- install the JDK version required by the project

Go:

- set deterministic `GOPROXY` when the network requires it
- run tests from the module containing selected tests

Rust:

- install system libraries required by crates
- keep cargo registry/build artifacts out of final package export
