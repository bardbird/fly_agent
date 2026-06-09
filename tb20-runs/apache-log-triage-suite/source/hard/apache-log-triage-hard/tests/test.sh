#!/usr/bin/env bash
set -euo pipefail

mkdir -p /logs/verifier
trap 'echo 0 > /logs/verifier/reward.txt' ERR

if [ "$PWD" = "/" ]; then
  echo "Error: No working directory set. Please set a WORKDIR in your Dockerfile before running this script."
  exit 1
fi

python /tests/test_outputs.py

echo 1 > /logs/verifier/reward.txt
