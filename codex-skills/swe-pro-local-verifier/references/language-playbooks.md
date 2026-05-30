# Language Playbooks

Use these checks to choose commands and Docker setup.

## Python

Signals:

- `pyproject.toml`, `setup.py`, `setup.cfg`, `requirements*.txt`, `tox.ini`, `pytest.ini`

Commands:

- `python3 -m pytest <selected_tests>`
- add `PYTHONPATH="$ROOT/repo:${PYTHONPATH:-}"` if imports fail due to source layout

Setup:

- `PIP_NO_CACHE_DIR=1 python3 -m pip install -e .`
- install test extras only when present and needed

## JavaScript / TypeScript

Signals:

- `package.json`, lockfiles, `vitest`, `jest`, `playwright`, `npm test`

Commands:

- `npm test -- <test>`
- `npx vitest run <test>`
- `npx jest <test>`

Setup:

- run install in the nearest package root
- use `npm ci` with `package-lock.json`
- use `corepack enable` for pnpm/yarn projects

## Java / Kotlin

Signals:

- `pom.xml`, `build.gradle`, `gradlew`, `settings.gradle`

Commands:

- `./gradlew test --tests <ClassName>`
- `mvn -Dtest=<ClassName> test`

Setup:

- prefer wrappers when available
- install JDK required by the build file

## Go

Signals:

- `go.mod`, `go.work`

Commands:

- `go test ./path -run <TestName>`

Setup:

- run from module root
- set `GOPROXY` only if network access needs it

## Rust

Signals:

- `Cargo.toml`, `Cargo.lock`

Commands:

- `cargo test -p <crate> <test_name>`
- `cargo test --test <integration_test>`

Setup:

- install system libraries used by linked crates
- keep selected pass-to-pass tests outside PR-modified tests
