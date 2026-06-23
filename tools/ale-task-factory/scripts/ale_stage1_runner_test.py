from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ale_stage1_runner as r


def _write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


class LoadTriggerTest(unittest.TestCase):
    def test_stage1(self):
        with tempfile.TemporaryDirectory() as d:
            trig = Path(d) / "trig.json"
            _write(trig, {"type": "stage1", "run_id": 9, "run_dir": d,
                          "stage1": {"framework_root": "/fw", "codex_model": "gpt-5.5",
                                     "tasks": [{"task_id": "d/t01", "title": "T1"}],
                                     "request": {"domain": "d", "scenario": "s", "difficulty": "easy"}}})
            payload = r.load_trigger(trig)
            self.assertEqual(payload["type"], "stage1")
            self.assertEqual(payload["stage1"]["tasks"], [{"task_id": "d/t01", "title": "T1"}])

    def test_rejects_empty_tasks(self):
        with tempfile.TemporaryDirectory() as d:
            trig = Path(d) / "trig.json"
            _write(trig, {"type": "stage1", "run_id": 9, "run_dir": d,
                          "stage1": {"framework_root": "/fw", "tasks": []}})
            with self.assertRaises(ValueError):
                r.load_trigger(trig)

    def test_rejects_wrong_type(self):
        with tempfile.TemporaryDirectory() as d:
            trig = Path(d) / "trig.json"
            _write(trig, {"type": "stage2", "run_id": 9, "run_dir": d,
                          "stage2": {"framework_root": "/fw"}})
            with self.assertRaises(ValueError):
                r.load_trigger(trig)


class CheckExactTaskIdsTest(unittest.TestCase):
    def _make_task_dir(self, base: Path, domain: str, name: str) -> None:
        d = base / "tasks" / domain / name
        d.mkdir(parents=True)
        (d / "task_card.json").write_text("{}", encoding="utf-8")  # _discover_task_dirs 需 task_card.json

    def test_pass_when_discovered_matches_expected(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            self._make_task_dir(base, "d", "t01")
            self._make_task_dir(base, "d", "t02")
            r.check_exact_task_ids(base, ["d/t01", "d/t02"])  # 不抛即通过

    def test_detects_missing_and_extra(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            self._make_task_dir(base, "d", "t01")
            self._make_task_dir(base, "d", "t03")  # 多生成
            with self.assertRaises(ValueError) as cm:
                r.check_exact_task_ids(base, ["d/t01", "d/t02"])  # 缺 t02
            self.assertIn("t02", str(cm.exception))
            self.assertIn("t03", str(cm.exception))

    def test_detects_all_missing_when_nothing_generated(self):
        with tempfile.TemporaryDirectory() as d:
            # codex 什么都没生成（tasks/ 不存在）
            with self.assertRaises(ValueError) as cm:
                r.check_exact_task_ids(Path(d), ["d/t01", "d/t02"])
            msg = str(cm.exception)
            self.assertIn("t01", msg)
            self.assertIn("t02", msg)
            # extra 应为空
            self.assertIn("extra=[]", msg)


class CheckAleVenvTest(unittest.TestCase):
    def test_failure_is_fatal_not_downgrade(self):
        with mock.patch("subprocess.run") as fake_run:
            class P:
                returncode = 1  # cua_bench import 失败
            fake_run.return_value = P()
            self.assertFalse(r.check_ale_venv(Path("/fw")))  # False → main 写 failed，绝不降级

    def test_success_returns_true(self):
        with mock.patch("subprocess.run") as fake_run:
            class P:
                returncode = 0
            fake_run.return_value = P()
            self.assertTrue(r.check_ale_venv(Path("/fw")))


class RunCodexStreamsStdoutTest(unittest.TestCase):
    def test_streams_stdout_not_open_log(self):
        with mock.patch("subprocess.run") as fake_run:
            class P:
                returncode = 0
            fake_run.return_value = P()
            with tempfile.TemporaryDirectory() as d:
                rc = r.run_codex(plan_path=Path(d) / "plan.json", output_dir=Path(d),
                                 cwd=Path(d), framework_root=Path(d))
            self.assertEqual(rc, 0)
            kwargs = fake_run.call_args.kwargs
            self.assertIs(kwargs.get("stdout"), sys.stdout)  # 透传，不再 open(codex.log)
            self.assertNotIn("capture_output", kwargs)


if __name__ == "__main__":
    unittest.main()
