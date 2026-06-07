#!/usr/bin/env bash
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3.12}"

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN=python3
fi

if [ ! -x "$SKILL_DIR/.venv/bin/python" ]; then
  "$PYTHON_BIN" -m venv "$SKILL_DIR/.venv"
fi

"$SKILL_DIR/.venv/bin/python" -m pip config --site set global.index-url "${PIP_INDEX_URL:-https://pypi.tuna.tsinghua.edu.cn/simple}"
"$SKILL_DIR/.venv/bin/python" -m pip install -U pip
"$SKILL_DIR/.venv/bin/python" -m pip install -U harbor
"$SKILL_DIR/.venv/bin/harbor" --version
