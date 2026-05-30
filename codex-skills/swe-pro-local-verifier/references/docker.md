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
