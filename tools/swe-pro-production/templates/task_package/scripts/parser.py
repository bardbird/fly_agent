#!/usr/bin/env python3
from __future__ import annotations
import json, re, sys
text = sys.stdin.read()
failed = re.search(r"(\d+) failed", text)
passed = re.search(r"(\d+) passed", text)
print(json.dumps({"passed": int(passed.group(1)) if passed else 0, "failed": int(failed.group(1)) if failed else 0, "success": failed is None}, sort_keys=True))
