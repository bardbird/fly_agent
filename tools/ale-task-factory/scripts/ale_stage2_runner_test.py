from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import ale_stage2_runner as r


def _write(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


class GetVerifiedTasksTest(unittest.TestCase):
    def test_only_from_summary(self):
        with tempfile.TemporaryDirectory() as d:
            _write(Path(d) / "summary.json", {
                "oracle_validation": {"by_task": [
                    {"task_id": "d/t01", "status": "verified", "oracle_score": 1.0},
                    {"task_id": "d/t02", "status": "blocked"},
                ]}})
            verified = r.get_verified_tasks(Path(d))
            self.assertEqual([t["task_id"] for t in verified], ["d/t01"])

    def test_no_summary_returns_empty(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "tasks" / "d" / "t01").mkdir(parents=True)
            (Path(d) / "tasks" / "d" / "t01" / "main.py").write_text("x", encoding="utf-8")
            (Path(d) / "tasks" / "d" / "t01" / "task_card.json").write_text("{}", encoding="utf-8")
            self.assertEqual(r.get_verified_tasks(Path(d)), [])

    def test_malformed_oracle_returns_empty(self):
        with tempfile.TemporaryDirectory() as d:
            _write(Path(d) / "summary.json", {"oracle_validation": {}})
            self.assertEqual(r.get_verified_tasks(Path(d)), [])


class LoadTriggerTest(unittest.TestCase):
    def test_stage2(self):
        with tempfile.TemporaryDirectory() as d:
            trigger = Path(d) / "trig.json"
            _write(trigger, {"type": "stage2", "run_id": 7, "run_dir": d,
                             "stage2": {"framework_root": "/fw", "agent": "claude_code",
                                        "model": "claude-sonnet-4-6", "timeout": 600}})
            payload = r.load_trigger(trigger)
            self.assertEqual(payload["type"], "stage2")
            self.assertEqual(payload["stage2"]["framework_root"], "/fw")


class RunOneTaskStreamsStdoutTest(unittest.TestCase):
    def test_no_capture_output_and_streams_stdout(self):
        with mock.patch("subprocess.run") as fake_run:
            class FakeProc:
                returncode = 0
            fake_run.return_value = FakeProc()
            r.run_one_task(Path("/fw"), Path("/tmp/exp.yaml"), "d/t01", timeout_s=60)
            kwargs = fake_run.call_args.kwargs
            self.assertNotIn("capture_output", kwargs)   # 不再静默
            self.assertIsNotNone(kwargs.get("stdout"))    # 透传到父进程 stdout


if __name__ == "__main__":
    unittest.main()
