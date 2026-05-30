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
- eval-shell parity: `bash -lc 'command -v python3 && python3 --version'`

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
- eval-shell parity: check the selected runner with `bash -lc 'command -v node && command -v npm'`, or `pnpm`/`yarn` when selected

## Java / Kotlin

Signals:

- `pom.xml`, `build.gradle`, `gradlew`, `settings.gradle`

Commands:

- `./gradlew test --tests <ClassName>`
- `mvn -Dtest=<ClassName> test`

Setup:

- prefer wrappers when available
- install JDK required by the build file
- eval-shell parity: `bash -lc 'command -v java && java -version'`, plus `mvn` or `./gradlew` when selected

## Go

Signals:

- `go.mod`, `go.work`

Commands:

- `go test ./path -run <TestName>`

Setup:

- run from module root
- set `GOPROXY` only if network access needs it
- eval-shell parity: `bash -lc 'command -v go && go version'`
- if login-shell PATH drops Go, prefix task commands with `export PATH=/usr/local/go/bin:/go/bin:$PATH &&`

## Rust

Signals:

- `Cargo.toml`, `Cargo.lock`

Commands:

- `cargo test -p <crate> <test_name>`
- `cargo test --test <integration_test>`

Setup:

- install system libraries used by linked crates
- keep selected pass-to-pass tests outside PR-modified tests
- eval-shell parity: `bash -lc 'command -v cargo && cargo --version'`
