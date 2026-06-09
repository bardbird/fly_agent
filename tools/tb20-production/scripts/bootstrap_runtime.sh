#!/usr/bin/env bash
set -euo pipefail

VENV_DIR="${TB20_RUNTIME_VENV:-/home/ubuntu/tb20-runtime/.venv}"
PYTHON_BIN="${PYTHON_BIN:-python3.12}"

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN=python3
fi

if [ ! -x "$VENV_DIR/bin/python" ]; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
fi

"$VENV_DIR/bin/python" -m pip config --site set global.index-url "${PIP_INDEX_URL:-https://pypi.tuna.tsinghua.edu.cn/simple}"
"$VENV_DIR/bin/python" -m pip install -U pip
"$VENV_DIR/bin/python" -m pip install -U terminal-bench harbor
"$VENV_DIR/bin/tb" --help >/dev/null
"$VENV_DIR/bin/harbor" --version
