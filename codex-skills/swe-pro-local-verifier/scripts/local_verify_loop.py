#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


def run(cmd: list[str], cwd: Path, allow_fail: bool = False) -> int:
    print("$ " + " ".join(cmd), flush=True)
    proc = subprocess.run(cmd, cwd=cwd)
    if proc.returncode and not allow_fail:
        raise SystemExit(proc.returncode)
    return proc.returncode


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Run deterministic SWE-Pro local verification checks.")
    parser.add_argument("package_dir")
    parser.add_argument("--toolkit-root", default="tools/swe-pro-production")
    parser.add_argument("--python", default=sys.executable)
    parser.add_argument("--skip-patch-check", action="store_true")
    args = parser.parse_args()

    package = Path(args.package_dir).resolve()
    toolkit = Path(args.toolkit_root).resolve()
    if not package.is_dir():
        raise SystemExit(f"package directory not found: {package}")
    if not toolkit.is_dir():
        raise SystemExit(f"toolkit root not found: {toolkit}")

    if not args.skip_patch_check:
        run(["bash", "scripts/verify_patch_application.sh"], package)

    run([args.python, str(toolkit / "scripts" / "package_task.py"), str(package), "--docker"], package.parent)

    validation_path = package / "logs" / "docker" / "validation.json"
    validation = load_json(validation_path)
    if not validation.get("ok"):
        print(json.dumps(validation, ensure_ascii=False, indent=2))
        return 1
    print(json.dumps({
        "status": "local_verification_passed",
        "package_path": str(package),
        "validation_json": str(validation_path),
        "image_tag": validation.get("image_tag", ""),
        "image_size": validation.get("image_size", 0),
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
