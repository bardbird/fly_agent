#!/usr/bin/env bash
set -euo pipefail
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
git apply --check "$ROOT/patches/gold.patch"
git apply --check "$ROOT/patches/test.patch"
echo "apply_checks_ok"
