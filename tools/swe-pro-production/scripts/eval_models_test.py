from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import eval_models


class EvalModelsPromptTest(unittest.TestCase):
    def test_extract_json_accepts_prose_and_unescaped_control_chars(self) -> None:
        response = (
            "I will return the edit now.\n\n"
            '{"edits":[{"type":"replace","file":"src/demo.go","find":"\told\nvalue","replace":"\tnew\nvalue"}]}'
            "\n\nDone."
        )

        parsed = eval_models.extract_json(response)

        self.assertEqual("src/demo.go", parsed["edits"][0]["file"])
        self.assertEqual("\told\nvalue", parsed["edits"][0]["find"])

    def test_extract_json_prefers_edit_object_when_multiple_objects_are_present(self) -> None:
        response = (
            '{"note":"analysis metadata"}\n'
            '{"edits":[{"type":"replace","file":"src/app.py","find":"old","replace":"new"}]}'
        )

        parsed = eval_models.extract_json(response)

        self.assertEqual("src/app.py", parsed["edits"][0]["file"])

    def test_extract_json_accepts_fenced_json_with_trailing_text(self) -> None:
        response = (
            "Patch follows.\n"
            "```json\n"
            '{"patch":"diff --git a/a.txt b/a.txt\\n--- a/a.txt\\n+++ b/a.txt\\n@@ -1 +1 @@\\n-old\\n+new\\n"}\n'
            "```\n"
            "No more changes."
        )

        parsed = eval_models.extract_json(response)

        self.assertIn("diff --git", parsed["patch"])

    def test_infrastructure_failures_are_not_valid_baseline_failures(self) -> None:
        messages = [
            "go: command not found",
            "xcrun: error: invalid active developer path",
            "bash: npm: command not found",
            "ModuleNotFoundError: No module named 'pytest'",
            "mvn: command not found",
            "cargo: command not found",
            "gcc: command not found",
            "g++: command not found",
            "php: command not found",
            "composer: command not found",
            "swift: command not found",
            "kotlinc: command not found",
            "Could not resolve dependencies for project acme:demo",
            "npm ERR! code EAI_AGAIN",
        ]
        for message in messages:
            with self.subTest(message=message):
                self.assertTrue(eval_models.is_infrastructure_failure(message))
        self.assertFalse(eval_models.is_infrastructure_failure("undefined: HeaderNavModuleAuth"))

    def test_standard_status_mapping_and_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            run_dir = package / "model_evaluation" / "demo" / "run_01"
            run_dir.mkdir(parents=True)
            (package / "task.json").write_text(json.dumps({
                "task_id": "instance_demo",
                "repo": "acme/demo",
                "base_commit": "abc123",
                "metadata": {"source_pr": "https://github.com/acme/demo/pull/1"},
                "evidence_links": ["https://github.com/acme/demo/issues/2"],
            }), encoding="utf-8")
            (run_dir / "eval.log").write_text("go: command not found\n", encoding="utf-8")
            result = {"passed": False, "error": "fail_to_pass infrastructure failure"}

            eval_models.write_evaluation_artifacts(package, run_dir, result, "demo-model")

            output = json.loads((run_dir / "test_output.json").read_text(encoding="utf-8"))
            report = (run_dir / "evaluation_report.md").read_text(encoding="utf-8")
            self.assertEqual("test_infra_failed", result["status"])
            self.assertEqual("test_infra_failed", output["status"])
            self.assertIn("- conclusion: test_infra_failed", report)

    def test_qwen_plus_metadata_uses_current_model_name(self) -> None:
        self.assertEqual(
            "qwen3_6_plus_pass_at_4",
            eval_models.model_eval_metadata_key("qwen3.6-plus"),
        )

    def test_agentic_prompt_uses_issue_not_test_or_patch_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            package.mkdir(exist_ok=True)
            (package / "problem_statement.md").write_text("unused fallback", encoding="utf-8")
            (package / "task.json").write_text(json.dumps({
                "repo": "acme/demo",
                "instance_id": "instance_1",
                "base_commit": "abc123",
                "repo_language": "go",
                    "problem_statement": (
                        "Users can access rankings while logged out.\n"
                        "Source PR: https://github.com/acme/demo/pull/1\n"
                        "Issue: https://github.com/acme/demo/issues/2\n"
                        "Fix commit: deadbeef\n"
                    "\n## Background\n"
                    "The upstream change fixes the behavior.\n"
                    "\n## Issue Report\n"
                    "Rankings should honor access control.\n"
                    "\n## Test Coverage\n"
                    "- middleware/secret_test.go\n"
                ),
                "requirements": "Fail-to-pass command: go test ./secret",
                "interface": "Selected tests: middleware/secret_test.go",
                "selected_test_files_to_run": ["middleware/secret_test.go"],
                "fail_to_pass": ["go test ./secret"],
                "pass_to_pass": ["go test ./safe"],
                "patch": "gold secret",
                "test_patch": "hidden assertion",
            }), encoding="utf-8")

            prompt = eval_models.default_prompt(package)

            self.assertIn("Users can access rankings", prompt)
            self.assertIn("Rankings should honor access control.", prompt)
            self.assertNotIn("Source PR", prompt)
            self.assertNotIn("github.com/acme/demo/issues/2", prompt)
            self.assertNotIn("Fix commit", prompt)
            self.assertNotIn("upstream change", prompt)
            self.assertNotIn("secret_test.go", prompt)
            self.assertNotIn("fail_to_pass", prompt)
            self.assertNotIn("Fail-to-pass", prompt)
            self.assertNotIn("pass_to_pass", prompt)
            self.assertNotIn("Selected tests", prompt)
            self.assertNotIn("hidden assertion", prompt)
            self.assertNotIn("gold secret", prompt)

    def test_agentic_prompt_includes_official_requirements_and_interface_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            package.mkdir(exist_ok=True)
            (package / "task.json").write_text(json.dumps({
                "repo": "acme/demo",
                "instance_id": "instance_1",
                "base_commit": "abc123",
                "repo_language": "typescript",
                "problem_statement": "Settings are not persisted after refresh.",
                "requirements": "- Persist the selected layout mode.\n- Preserve existing API behavior.",
                "interface": "Type: Function\nName: saveLayoutMode\nPath: src/settings.ts",
                "fail_to_pass": ["npm test -- hidden"],
                "pass_to_pass": ["npm test -- existing"],
                "patch": "diff --git secret",
                "test_patch": "hidden assertions",
            }), encoding="utf-8")

            prompt = eval_models.default_prompt(package)

            self.assertIn("Settings are not persisted after refresh.", prompt)
            self.assertIn("Requirements:", prompt)
            self.assertIn("Persist the selected layout mode.", prompt)
            self.assertIn("New interfaces introduced:", prompt)
            self.assertIn("saveLayoutMode", prompt)
            self.assertNotIn("npm test", prompt)
            self.assertNotIn("hidden assertions", prompt)

    def test_agentic_prompt_does_not_duplicate_embedded_official_sections(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            package.mkdir(exist_ok=True)
            (package / "task.json").write_text(json.dumps({
                "repo": "acme/demo",
                "instance_id": "instance_1",
                "base_commit": "abc123",
                "repo_language": "go",
                "problem_statement": (
                    "Settings are not persisted after refresh.\n\n"
                    "Requirements:\n"
                    "- Persist the selected layout mode.\n\n"
                    "New interfaces introduced:\n"
                    "None"
                ),
                "requirements": "- Persist the selected layout mode.",
                "interface": "None",
                "fail_to_pass": ["go test ./secret"],
                "pass_to_pass": ["go test ./safe"],
                "patch": "diff --git secret",
                "test_patch": "hidden assertions",
            }), encoding="utf-8")

            prompt = eval_models.default_prompt(package)

            self.assertEqual(1, prompt.count("Requirements:"))
            self.assertEqual(1, prompt.count("New interfaces introduced:"))

    def test_agentic_tools_are_repo_scoped(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            repo = package / "repo"
            repo.mkdir()
            (repo / "main.go").write_text("package main\nfunc main() {}\n", encoding="utf-8")

            listing = eval_models.agentic_tool_observation(package, {"action": "list_dir", "path": "."})
            content = eval_models.agentic_tool_observation(package, {"action": "read_file", "path": "main.go", "start": 1, "end": 1})

            self.assertIn("main.go", listing)
            self.assertIn("1: package main", content)
            with self.assertRaisesRegex(ValueError, "escapes repository"):
                eval_models.agentic_tool_observation(package, {"action": "read_file", "path": "../task.json"})

    def test_materialize_model_patch_captures_created_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            repo = package / "repo"
            run_dir = package / "run"
            repo.mkdir(parents=True)
            run_dir.mkdir()
            subprocess.run(["git", "init"], cwd=repo, check=True, stdout=subprocess.PIPE)
            subprocess.run(["git", "config", "user.email", "test@example.com"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.name", "Test"], cwd=repo, check=True)
            (repo / "README.md").write_text("base\n", encoding="utf-8")
            subprocess.run(["git", "add", "README.md"], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "base"], cwd=repo, check=True, stdout=subprocess.PIPE)
            (run_dir / "raw_response.txt").write_text(
                "diff --git a/src/new_file.py b/src/new_file.py\n"
                "new file mode 100644\n"
                "index 0000000..b6c89d4\n"
                "--- /dev/null\n"
                "+++ b/src/new_file.py\n"
                "@@ -0,0 +1 @@\n"
                "+print('new')\n",
                encoding="utf-8",
            )

            files = eval_models.materialize_model_patch(package, run_dir, "model.patch")
            patch = (run_dir / "model.patch").read_text(encoding="utf-8")

            self.assertEqual(["src/new_file.py"], files)
            self.assertIn("new file mode", patch)
            self.assertIn("+print('new')", patch)

    def test_materialize_model_patch_rejects_json_in_strict_diff_mode(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            repo = package / "repo"
            run_dir = package / "run"
            repo.mkdir(parents=True)
            run_dir.mkdir()
            subprocess.run(["git", "init"], cwd=repo, check=True, stdout=subprocess.PIPE)
            (run_dir / "raw_response.txt").write_text(
                '{"edits":[{"type":"write","file":"src/new_file.py","content":"print()\\n"}]}',
                encoding="utf-8",
            )

            with self.assertRaisesRegex(RuntimeError, "no unified diff"):
                eval_models.materialize_model_patch(package, run_dir, "model.patch")

if __name__ == "__main__":
    unittest.main()
