#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import finalize_task_metadata


class FinalizeTaskMetadataTest(unittest.TestCase):
    def test_finalize_backfills_delivery_metadata_and_refreshes_checksums(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for relative in finalize_task_metadata.TASK_SPEC_FILES:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(f"{relative}\n", encoding="utf-8")
            task = {
                "repo": "acme/demo",
                "repo_language": "go",
                "pass_to_pass": ["go test ./common -run Test -count=1"],
                "docker_image": "local/demo:latest",
                "dockerhub_tag": "local/demo:latest",
                "metadata": {
                    "patch_stats": {
                        "test_paths": ["tests/test_demo.go"],
                    },
                    "verification": {
                        "test_patch_denoise_assessment": {
                            "summary": "route-level behavior oracle",
                            "metrics": {
                                "changed_lines": 20,
                                "assertion_lines": 5,
                            },
                        },
                    },
                },
            }
            (root / "patches" / "gold.patch").write_text(
                "diff --git a/app.go b/app.go\n--- a/app.go\n+++ b/app.go\n@@ -1 +1 @@\n-old\n+new\n",
                encoding="utf-8",
            )
            (root / "patches" / "test.patch").write_text(
                "diff --git a/tests/test_demo.go b/tests/test_demo.go\n--- a/tests/test_demo.go\n+++ b/tests/test_demo.go\n@@ -0,0 +1 @@\n+test\n",
                encoding="utf-8",
            )
            (root / "task.json").write_text(json.dumps(task, indent=2) + "\n", encoding="utf-8")
            validation = {
                "image_tag": "local/demo:latest",
                "image_id": "sha256:abc",
                "image_size": 123,
                "ok": True,
                "validation": {
                    "baseline": {"exit": 1, "result": "fails", "expected": "fails"},
                    "fixed": {"exit": 0, "result": "passes", "expected": "passes"},
                    "pass-to-pass": {"exit": 0, "result": "passes", "expected": "passes"},
                },
                "task_spec_checksums": {},
            }
            (root / "logs" / "docker").mkdir(parents=True)
            (root / "logs" / "docker" / "validation.json").write_text(
                json.dumps(validation, indent=2) + "\n",
                encoding="utf-8",
            )
            (root / "docker-image").mkdir()
            (root / "docker-image" / "image_info.txt").write_text(
                json.dumps(
                    {
                        "image_tag": "local/demo:latest",
                        "image_id": "sha256:abc",
                        "image_size": 123,
                        "image_tar": "docker-image/demo.tar",
                        "image_tar_sha256": "deadbeef",
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            (root / "model_evaluation" / "opus4.7_pass8_swebench_agentic").mkdir(parents=True)
            (root / "model_evaluation" / "opus4.7_pass8_swebench_agentic" / "summary.json").write_text(
                json.dumps(
                    {
                        "model": "claude-opus-4-7",
                        "base_url": "[REDACTED_BASE_URL]",
                        "output_dir": "model_evaluation/opus4.7_pass8_swebench_agentic",
                        "attempts": 8,
                        "passes": 2,
                        "pass_rate": 0.25,
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            (root / "model_evaluation" / "qwen3.6_plus_pass4_behavior").mkdir(parents=True)
            (root / "model_evaluation" / "qwen3.6_plus_pass4_behavior" / "summary.json").write_text(
                json.dumps(
                    {
                        "model": "qwen3.6-plus",
                        "base_url": "[REDACTED_BASE_URL]",
                        "output_dir": "model_evaluation/qwen3.6_plus_pass4_behavior",
                        "attempts": 4,
                        "passes": 0,
                        "pass_rate": 0.0,
                    },
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            (root / "batch_evidence").mkdir()
            (root / "batch_evidence" / "language_and_category_distribution.md").write_text("batch\n", encoding="utf-8")
            (root / "review").mkdir()
            for name in ("reviewer_1.md", "reviewer_2.md", "reviewer_3.md", "adjudication_and_calibration.md"):
                (root / "review" / name).write_text("review\n", encoding="utf-8")

            result = finalize_task_metadata.finalize(root)

            self.assertTrue(result["task_json_changed"])
            updated_task = json.loads((root / "task.json").read_text(encoding="utf-8"))
            metadata = updated_task["metadata"]
            self.assertEqual("Go", metadata["language"])
            self.assertEqual("fails as expected", metadata["verification"]["baseline"])
            self.assertEqual("passes", metadata["verification"]["fixed"])
            self.assertIn("go test ./common", metadata["verification"]["pass_to_pass"])
            self.assertEqual("docker-image/demo.tar", metadata["docker"]["image_tar"])
            self.assertEqual("sha256:abc", metadata["docker_image_id"])
            self.assertEqual("deadbeef", metadata["docker_image_tar_sha256"])
            self.assertIn("opus4_7_pass_at_8", metadata["model_evaluation"])
            self.assertEqual(
                "model_evaluation/qwen3.6_plus_pass4_behavior/summary.json",
                metadata["model_evaluation"]["qwen3_6_plus_pass_at_4_summary_file"],
            )
            self.assertEqual(
                "qwen3.6-plus",
                metadata["model_evaluation"]["qwen3_6_plus_model"],
            )
            self.assertEqual(5, metadata["patch_stats"]["test_patch_lines"])
            self.assertEqual(1, metadata["patch_stats"]["test_files"])
            self.assertEqual("completed_for_this_package", metadata["expert_review"]["status"])
            updated_validation = json.loads((root / "logs" / "docker" / "validation.json").read_text(encoding="utf-8"))
            self.assertEqual(
                finalize_task_metadata.sha256_file(root / "task.json"),
                updated_validation["task_spec_checksums"]["task.json"],
            )


if __name__ == "__main__":
    unittest.main()
