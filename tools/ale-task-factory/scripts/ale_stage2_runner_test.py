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

    def test_rejects_wrong_type(self):
        with tempfile.TemporaryDirectory() as d:
            trigger = Path(d) / "trig.json"
            _write(trigger, {"type": "stage1", "run_id": 1, "run_dir": d,
                             "stage1": {"framework_root": "/fw", "tasks": []}})
            with self.assertRaises(ValueError):
                r.load_trigger(trigger)

    def test_rejects_missing_framework_root(self):
        with tempfile.TemporaryDirectory() as d:
            trigger = Path(d) / "trig.json"
            _write(trigger, {"type": "stage2", "run_id": 1, "run_dir": d,
                             "stage2": {"agent": "claude_code"}})  # 无 framework_root
            with self.assertRaises(ValueError):
                r.load_trigger(trigger)


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


class PrepareTasksTest(unittest.TestCase):
    def _write_framework_configs(self, framework: Path) -> None:
        agent = framework / "configs" / "agents" / "claude_code.yaml"
        agent.parent.mkdir(parents=True, exist_ok=True)
        agent.write_text(
            "harness: claude_code\n"
            "model: old-model\n"
            "config:\n"
            "  provider: openrouter\n",
            encoding="utf-8",
        )
        env = framework / "configs" / "environments" / "docker.yaml"
        env.parent.mkdir(parents=True, exist_ok=True)
        env.write_text(
            "snapshots:\n"
            "  cpu-free-ubuntu:\n"
            "    provider: docker\n"
            "task_data_source: local:task-data\n"
            "output_path: null\n",
            encoding="utf-8",
        )

    def test_installs_local_task_data_for_docker_provider(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            run_dir = root / "run"
            framework = root / "framework"
            self._write_framework_configs(framework)
            task = run_dir / "tasks" / "business_finance" / "task_authoring_01"
            (task / "input").mkdir(parents=True)
            (task / "reference").mkdir()
            (task / "main.py").write_text("x", encoding="utf-8")
            (task / "task_card.json").write_text("{}", encoding="utf-8")
            (task / "input" / "brief.md").write_text("visible", encoding="utf-8")
            (task / "reference" / "answer.json").write_text("hidden", encoding="utf-8")

            r.prepare_tasks(
                run_dir,
                framework,
                [{"task_id": "business_finance/task_authoring_01"}],
                model="claude-sonnet-4-6",
                provider="direct",
            )

            linked = framework / "tasks" / "business_finance" / "task_authoring_01"
            self.assertTrue(linked.is_symlink())
            data = framework / "task-data" / "business_finance" / "task_authoring_01" / "base"
            self.assertEqual((data / "input" / "brief.md").read_text(encoding="utf-8"), "visible")
            self.assertEqual((data / "reference" / "answer.json").read_text(encoding="utf-8"), "hidden")
            self.assertIn("provider: direct", (run_dir / "configs" / "claude_code_stage2.yaml").read_text(encoding="utf-8"))
            self.assertIn("model: claude-sonnet-4-6", (run_dir / "configs" / "claude_code_stage2.yaml").read_text(encoding="utf-8"))
            self.assertIn("output_path: local", (run_dir / "configs" / "docker_stage2.yaml").read_text(encoding="utf-8"))


class TimeoutParsingTest(unittest.TestCase):
    def test_accepts_current_vm_timeout(self):
        with tempfile.TemporaryDirectory() as d:
            card = Path(d) / "task_card.json"
            _write(card, {"vm": {"timeout": 1234}})
            self.assertEqual(r.read_task_timeout(card, 7200), 1234)

    def test_accepts_legacy_timeout_s(self):
        with tempfile.TemporaryDirectory() as d:
            card = Path(d) / "task_card.json"
            _write(card, {"vm": {"timeout_s": 2345, "timeout": 1234}})
            self.assertEqual(r.read_task_timeout(card, 7200), 2345)


class CollectTaskResultTest(unittest.TestCase):
    def test_matches_exact_task_slug(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            run_dir = root / "run"
            log_root = run_dir / "logs" / "ale"
            wrong = log_root / "claude_code" / "model" / "d__other" / "v0" / "20260101"
            right = log_root / "claude_code" / "model" / "d__t01" / "v0" / "20260102"
            wrong.mkdir(parents=True)
            right.mkdir(parents=True)
            _write(wrong / "run.json", {"status": "completed", "score": 0.1})
            _write(right / "run.json", {"status": "completed", "score": 0.9})

            class Proc:
                returncode = 0

            result = r.collect_task_result(run_dir, log_root, "d/t01", Proc(), 1.0)
            self.assertEqual(result["score"], 0.9)


if __name__ == "__main__":
    unittest.main()
