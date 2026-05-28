from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

import export_swebench


class ExportSwebenchTest(unittest.TestCase):
    def test_exports_instance_and_prediction_jsonl(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            package = root / "production-task-demo-7"
            (package / "patches").mkdir(parents=True)
            (package / "model_evaluation" / "qwen3.6_flash_pass4" / "run_01").mkdir(parents=True)
            (package / "model_evaluation" / "opus4.7_pass8_json_edits_v2" / "run_01").mkdir(parents=True)
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "repo": "acme/demo",
                        "base_commit": "abc123",
                        "problem_statement": "Fix demo bug.",
                        "fail_to_pass": ["pytest tests/test_demo.py::test_bug"],
                        "pass_to_pass": ["pytest tests/test_demo.py::test_existing"],
                        "metadata": {"source_pr": "https://github.com/acme/demo/pull/7"},
                    }
                ),
                encoding="utf-8",
            )
            (package / "patches" / "gold.patch").write_text(
                "diff --git a/demo.py b/demo.py\n--- a/demo.py\n+++ b/demo.py\n",
                encoding="utf-8",
            )
            (package / "patches" / "test.patch").write_text(
                "diff --git a/tests/test_demo.py b/tests/test_demo.py\n",
                encoding="utf-8",
            )
            (package / "model_evaluation" / "qwen3.6_flash_pass4" / "summary.json").write_text(
                json.dumps({"model": "qwen3.6-flash", "attempts": 1, "passes": 0}),
                encoding="utf-8",
            )
            (package / "model_evaluation" / "qwen3.6_flash_pass4" / "run_01" / "model.patch").write_text(
                "diff --git a/q.py b/q.py\n--- a/q.py\n+++ b/q.py\n",
                encoding="utf-8",
            )
            (package / "model_evaluation" / "opus4.7_pass8_json_edits_v2" / "summary.json").write_text(
                json.dumps({"model": "claude-opus-4-7", "attempts": 1, "passes": 1}),
                encoding="utf-8",
            )
            (package / "model_evaluation" / "opus4.7_pass8_json_edits_v2" / "run_01" / "model.patch").write_text(
                "diff --git a/o.py b/o.py\n--- a/o.py\n+++ b/o.py\n",
                encoding="utf-8",
            )

            out = root / "swebench"
            result = export_swebench.export_package(package, out)

            self.assertEqual((out / "dataset.jsonl").resolve(), result["dataset"])
            dataset = [json.loads(line) for line in (out / "dataset.jsonl").read_text().splitlines()]
            self.assertEqual(1, len(dataset))
            instance = dataset[0]
            self.assertEqual("production-task-demo-7", instance["instance_id"])
            self.assertEqual("acme/demo", instance["repo"])
            self.assertEqual("abc123", instance["base_commit"])
            self.assertEqual(["pytest tests/test_demo.py::test_bug"], instance["FAIL_TO_PASS"])
            self.assertEqual(["pytest tests/test_demo.py::test_existing"], instance["PASS_TO_PASS"])
            self.assertIn("diff --git a/tests/test_demo.py", instance["test_patch"])

            predictions = [json.loads(line) for line in (out / "predictions.jsonl").read_text().splitlines()]
            self.assertEqual(
                [
                    ("qwen3.6-flash", "diff --git a/q.py b/q.py"),
                    ("claude-opus-4-7", "diff --git a/o.py b/o.py"),
                ],
                [(row["model_name_or_path"], row["model_patch"].splitlines()[0]) for row in predictions],
            )
            self.assertTrue((out / "manifest.json").is_file())


if __name__ == "__main__":
    unittest.main()
