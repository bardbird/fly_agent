#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


GIB = 1024 ** 3


def load_validation(package: Path) -> dict:
    path = package / "logs" / "docker" / "validation.json"
    if not path.is_file():
        raise SystemExit(f"missing validation evidence: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Check SWE-Pro Docker image size budget.")
    parser.add_argument("package_dir")
    parser.add_argument("--soft-gib", type=float, default=8.0)
    parser.add_argument("--hard-gib", type=float, default=12.0)
    args = parser.parse_args()

    package = Path(args.package_dir).resolve()
    validation = load_validation(package)
    size = int(validation.get("image_size") or 0)
    size_gib = size / GIB if size else 0.0
    if size and size_gib > args.hard_gib:
        status = "hard_fail"
    elif size and size_gib > args.soft_gib:
        status = "soft_warn"
    else:
        status = "ok"

    result = {
        "package_path": str(package),
        "image_tag": validation.get("image_tag", ""),
        "image_size_bytes": size,
        "image_size_gib": round(size_gib, 3),
        "soft_gib": args.soft_gib,
        "hard_gib": args.hard_gib,
        "budget_status": status,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2 if status == "hard_fail" else 0


if __name__ == "__main__":
    raise SystemExit(main())
