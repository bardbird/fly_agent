#!/usr/bin/env python3
from __future__ import annotations

import argparse
import fcntl
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a command under a package-local SWE AI lock.")
    parser.add_argument("package_dir")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()

    package = Path(args.package_dir).resolve()
    if not package.is_dir():
        raise SystemExit(f"package directory not found: {package}")
    if not args.command:
        raise SystemExit("command is required")

    lock_dir = package / ".swe-ai"
    lock_dir.mkdir(exist_ok=True)
    lock_path = lock_dir / "lock"
    with lock_path.open("w", encoding="utf-8") as lock_file:
        fcntl.flock(lock_file, fcntl.LOCK_EX | fcntl.LOCK_NB)
        lock_file.write("locked\n")
        lock_file.flush()
        proc = subprocess.run(args.command, cwd=package)
        return proc.returncode


if __name__ == "__main__":
    raise SystemExit(main())
