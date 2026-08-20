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
                                 cwd=Path(d), framework_root=Path(d), codex_model="gpt-5.5")
            self.assertEqual(rc, 0)
            kwargs = fake_run.call_args.kwargs
            self.assertIs(kwargs.get("stdout"), sys.stdout)  # 透传，不再 open(codex.log)
            self.assertNotIn("capture_output", kwargs)
            args = fake_run.call_args.args[0]  # cmd list
            self.assertIn("--model", args)
            self.assertIn("gpt-5.5", args)
            prompt = args[-1]
            self.assertIn("Do not enumerate, search, or read the official ALE tasks corpus", prompt)
            self.assertIn("tasks/demo/hello/main.py", prompt)
            self.assertIn("rg/find", prompt)


class EstimateCodexProgressTest(unittest.TestCase):
    def test_base_when_nothing_generated(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(r._estimate_codex_progress(Path(d)), 20)

    def test_advances_with_artifacts(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            (base / "generated" / "stages").mkdir(parents=True)
            (base / "generated" / "stages" / "brief.md").write_text("x", encoding="utf-8")
            self.assertEqual(r._estimate_codex_progress(base), 40)
            task_dir = base / "tasks" / "d" / "t01"
            task_dir.mkdir(parents=True)
            (task_dir / "main.py").write_text("x", encoding="utf-8")
            self.assertEqual(r._estimate_codex_progress(base), 85)


class ValidateGeneratedTasksTest(unittest.TestCase):
    def test_links_task_before_dry_run_and_loader(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            output = root / "run"
            framework = root / "framework"
            task = output / "tasks" / "d" / "t01"
            task.mkdir(parents=True)
            (task / "main.py").write_text("x", encoding="utf-8")
            (task / "task_card.json").write_text("{}", encoding="utf-8")
            _write(task / "oracle-logs" / "oracle-evidence.json", {
                "task_id": "d/t01",
                "status": "verified",
                "oracle": {
                    "score": 1.0,
                    "grader_check_ok": True,
                    "details": "ok",
                },
                "blocked_reason": None,
            })

            def fake_dry_run(framework_root: Path, task_path: str):
                self.assertTrue((framework_root / "tasks" / "d" / "t01").is_symlink())
                return {"ok": True, "output": task_path, "error": None}

            def fake_loader(_uv: str, framework_root: Path, loader_path: str):
                self.assertEqual(loader_path, "tasks/d/t01")
                self.assertTrue((framework_root / "tasks" / "d" / "t01").is_symlink())
                return {"ok": True, "error": ""}

            with mock.patch.object(r, "run_ale_dry_run", side_effect=fake_dry_run), \
                 mock.patch.object(r, "_invoke_task_loader", side_effect=fake_loader), \
                 mock.patch.object(r, "_find_uv", return_value="uv"):
                checks = r.validate_generated_tasks(
                    output,
                    framework,
                    r.TaskRequest(
                        domain="d",
                        task_id="t01",
                        title="",
                        scenario="",
                        difficulty="easy",
                        input_mode="",
                        output_mode="",
                        verification_mode="",
                        reference_strategy="",
                        framework_root=str(framework),
                    ),
                )

            self.assertEqual(len(checks), 1)
            self.assertEqual(checks[0].status, "verified")
            self.assertFalse((framework / "tasks" / "d" / "t01").exists())


if __name__ == "__main__":
    unittest.main()
