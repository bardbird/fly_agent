from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ale_progress import write_progress, is_terminal


class AleProgressTest(unittest.TestCase):
    def test_write_progress_atomic_and_readable(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "stage1_progress.json"
            write_progress(p, stage="stage1", phase="starting", percent=0,
                           counts={"total": 1})
            self.assertEqual(json.loads(p.read_text(encoding="utf-8")), {
                "stage": "stage1", "phase": "starting", "percent": 0,
                "counts": {"total": 1}, "current_task": None, "message": None,
            })
            # 原子写：不留 .tmp 残留
            self.assertFalse((Path(d) / "stage1_progress.json.tmp").exists())

    def test_write_progress_overwrites_previous_terminal(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "stage2_progress.json"
            write_progress(p, stage="stage2", phase="done", percent=100,
                           counts={"total": 2, "completed": 2})
            write_progress(p, stage="stage2", phase="starting", percent=0,
                           counts={"total": 2})
            data = json.loads(p.read_text(encoding="utf-8"))
            # 新值覆盖旧 done（gateway 重跑重置语义）
            self.assertEqual(data["phase"], "starting")

    def test_is_terminal(self):
        self.assertTrue(is_terminal("done"))
        self.assertTrue(is_terminal("failed"))
        self.assertFalse(is_terminal("codex_running"))
        self.assertFalse(is_terminal(None))


if __name__ == "__main__":
    unittest.main()
