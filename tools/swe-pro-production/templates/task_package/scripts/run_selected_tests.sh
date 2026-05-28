#!/usr/bin/env bash
set -euo pipefail
MODE="${1:-baseline}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/repo"
if [ ! -d .git ]; then
  git init -q
  git config user.email "fly-agent@example.invalid"
  git config user.name "fly-agent"
  git add -A
  git commit -q -m "baseline snapshot"
fi
git reset --hard HEAD >/dev/null
git clean -fd >/dev/null
case "$MODE" in
  baseline)
    git apply "$ROOT/patches/test.patch"
    {{FAIL_TO_PASS_CMD}}
    ;;
  fixed)
    git apply "$ROOT/patches/gold.patch"
    git apply "$ROOT/patches/test.patch"
    {{FAIL_TO_PASS_CMD}}
    ;;
  pass-to-pass)
    git apply "$ROOT/patches/gold.patch"
    {{PASS_TO_PASS_CMD}}
    ;;
  *)
    echo "usage: run_selected_tests.sh [baseline|fixed|pass-to-pass]" >&2
    exit 2
    ;;
esac
