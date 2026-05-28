#!/usr/bin/env python3
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import eval_with_swe_agent


class EvalWithSweAgentTest(unittest.TestCase):
    def test_problem_statement_omits_oracle_and_patch_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "problem_statement.md").write_text(
                "Fix user login\nIssue: https://github.com/acme/project/issues/1\n\n## Test Coverage\nDo not include this section\n",
                encoding="utf-8",
            )
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "instance_id": "instance_demo",
                        "repo": "acme/project",
                        "base_commit": "abc123",
                        "problem_statement": "Use file content instead",
                        "patch": "diff --git a/app.py b/app.py\n",
                        "test_patch": "diff --git a/test_app.py b/test_app.py\n",
                        "fail_to_pass": ["pytest tests/test_app.py::test_login"],
                        "pass_to_pass": ["pytest tests/test_app.py::test_existing"],
                    }
                ),
                encoding="utf-8",
            )

            text = eval_with_swe_agent.write_swe_agent_problem_statement(package)

            self.assertIn("Fix user login", text)
            self.assertIn("Submission is mandatory", text)
            self.assertIn("the final action must be exactly `submit`", text)
            self.assertNotIn("github.com/acme/project/issues/1", text)
            self.assertNotIn("Test Coverage", text)
            self.assertNotIn("diff --git", text)
            self.assertFalse(eval_with_swe_agent.contains_forbidden_model_input(text))
            self.assertTrue((package / "model_evaluation" / "_swe_agent_problem_statement.md").is_file())

    def test_extract_patch_prefers_prediction_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            (run_dir / "prediction.json").write_text(
                json.dumps({"model_patch": "diff --git a/a.py b/a.py\n--- a/a.py\n+++ b/a.py\n@@ -1 +1 @@\n-old\n+new\n"}),
                encoding="utf-8",
            )

            patch = eval_with_swe_agent.extract_patch_from_swe_agent_artifacts(run_dir, "")

            self.assertTrue(patch.startswith("diff --git"))
            self.assertIn("+new", patch)

    def test_extract_patch_preserves_trailing_blank_context_from_prediction(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            patch_text = (
                "diff --git a/a.py b/a.py\n"
                "--- a/a.py\n"
                "+++ b/a.py\n"
                "@@ -1,3 +1,4 @@\n"
                " line1\n"
                "+inserted\n"
                " line2\n"
                " \n"
            )
            (run_dir / "prediction.pred").write_text(
                json.dumps({"model_patch": patch_text}),
                encoding="utf-8",
            )

            patch = eval_with_swe_agent.extract_patch_from_swe_agent_artifacts(run_dir, "")

            self.assertEqual(patch_text, patch)

    def test_reset_repo_removes_ignored_build_artifacts_before_model_eval(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            repo = package / "repo"
            repo.mkdir()
            subprocess.run(["git", "-C", str(repo), "init"], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
            (repo / ".gitignore").write_text("target/\n", encoding="utf-8")
            (repo / "src").mkdir()
            (repo / "src" / "lib.rs").write_text("pub fn demo() {}\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-m", "base"], check=True, stdout=subprocess.DEVNULL)
            (repo / "target" / "debug").mkdir(parents=True)
            (repo / "target" / "debug" / "large.o").write_text("artifact\n", encoding="utf-8")

            eval_with_swe_agent.reset_repo(package)

            self.assertFalse((repo / "target").exists())

    def test_prune_repo_build_artifacts_keeps_tracked_config_dirs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            repo = package / "repo"
            repo.mkdir()
            subprocess.run(["git", "-C", str(repo), "init"], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
            (repo / ".cargo").mkdir()
            (repo / ".cargo" / "config.toml").write_text("[net]\ngit-fetch-with-cli = true\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-m", "base"], check=True, stdout=subprocess.DEVNULL)

            removed = eval_with_swe_agent.prune_repo_build_artifacts(package)

            self.assertEqual([], removed)
            self.assertTrue((repo / ".cargo" / "config.toml").is_file())
            self.assertEqual("", subprocess.check_output(["git", "-C", str(repo), "status", "--short"], text=True))

    def test_materialize_patch_preserves_trailing_hunk_context(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            repo = package / "repo"
            run_dir = package / "model_evaluation" / "demo" / "run_01"
            run_dir.mkdir(parents=True)
            repo.mkdir()
            subprocess.run(["git", "-C", str(repo), "init"], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
            (repo / "a.py").write_text("line1\nline2\nline3\n\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "a.py"], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-m", "base"], check=True, stdout=subprocess.DEVNULL)
            (repo / "a.py").write_text("line1\ninserted\nline2\nline3\n\n", encoding="utf-8")
            patch_text = subprocess.check_output(["git", "-C", str(repo), "diff"], text=True)
            subprocess.run(["git", "-C", str(repo), "reset", "--hard", "HEAD"], check=True, stdout=subprocess.DEVNULL)

            changed = eval_with_swe_agent.materialize_patch_from_swe_agent(package, run_dir, patch_text)

            self.assertEqual(["a.py"], changed)
            self.assertEqual(patch_text, (run_dir / "candidate.patch").read_text(encoding="utf-8"))
            self.assertTrue((run_dir / "model.patch").is_file())

    def test_redacts_base_url_from_command_and_logs(self) -> None:
        command = [
            "sweagent",
            "run",
            "--base-url",
            "https://secret-base.example/v1",
            "--agent.model.api_base=https://secret-agent.example/v1",
            "--agent.model.api_key=secret-token-for-redaction-test",
            "--agent.model.name=openai/demo",
        ]

        display = eval_with_swe_agent.command_for_display(command)
        redacted = eval_with_swe_agent.redact_secret_log_text(
            'base_url: "https://secret-base.example/v1"\n'
            "baseUrl: https://secret-camel.example/v1\n"
            "api_base=https://secret-agent.example/v1\n"
            "--agent.model.api_base=https://secret-cli.example/v1\n"
        )

        self.assertNotIn("secret-base.example", display)
        self.assertNotIn("secret-agent.example", display)
        self.assertNotIn("secret-token-for-redaction-test", display)
        self.assertIn("[REDACTED_BASE_URL]", display)
        self.assertNotIn("secret-base.example", redacted)
        self.assertNotIn("secret-camel.example", redacted)
        self.assertNotIn("secret-agent.example", redacted)
        self.assertNotIn("secret-cli.example", redacted)
        self.assertIn("[REDACTED_BASE_URL]", redacted)

    def test_swe_agent_api_key_reference_uses_env_name_not_secret(self) -> None:
        self.assertEqual("$QWEN_API_KEY", eval_with_swe_agent.swe_agent_api_key_reference("QWEN_API_KEY"))
        with self.assertRaises(ValueError):
            eval_with_swe_agent.swe_agent_api_key_reference("QWEN_API_KEY=sk-secret")

    def test_swe_agent_command_passes_real_key_and_redacts_display(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            run_dir = package / "model_evaluation" / "demo" / "run_01"
            run_dir.mkdir(parents=True)
            args = SimpleNamespace(
                swe_agent_root=Path("/tmp/swe-agent"),
                model="qwen3.6-flash",
                provider="openai",
                base_url="https://dashscope.example/v1",
                api_key_env="QWEN_API_KEY",
                api_key="super-secret-token",
                temperature=0.7,
                max_tokens=8192,
                agent_max_steps=20,
                max_input_tokens=22000,
                timeout=30,
                enable_thinking="false",
                history_observations=4,
            )
            captured = {}

            def fake_run(cmd, cwd, env=None, timeout=None):
                captured["cmd"] = cmd
                captured["env"] = env
                return 0, ""

            with patch.object(eval_with_swe_agent, "read_task", return_value={"base_commit": "abc123"}), \
                    patch.object(eval_with_swe_agent, "resolve_sweagent_bin", return_value="/tmp/sweagent"), \
                    patch.object(eval_with_swe_agent, "reset_repo"), \
                    patch.object(eval_with_swe_agent, "prune_repo_build_artifacts"), \
                    patch.object(eval_with_swe_agent, "write_swe_agent_guard_config", return_value=package / "guard.yaml"), \
                    patch.object(eval_with_swe_agent, "run", side_effect=fake_run), \
                    patch.object(eval_with_swe_agent, "extract_patch_from_swe_agent_artifacts", return_value="diff --git\n"), \
                    patch.object(eval_with_swe_agent, "materialize_patch_from_swe_agent", return_value=["src/lib.rs"]), \
                    patch.object(eval_with_swe_agent, "evaluate_model_patch", return_value={"passed": False}), \
                    patch.object(eval_with_swe_agent, "write_evaluation_artifacts"):
                eval_with_swe_agent.run_swe_agent_attempt(
                    package,
                    run_dir,
                    args,
                    package / "problem.md",
                    None,
                    "validation-image",
                )

            command_text = " ".join(captured["cmd"])
            self.assertIn("--agent.model.api_key=super-secret-token", command_text)
            self.assertNotIn("super-secret-token", eval_with_swe_agent.command_for_display(captured["cmd"]))
            self.assertEqual("super-secret-token", captured["env"]["QWEN_API_KEY"])
            self.assertEqual("super-secret-token", captured["env"]["OPENAI_API_KEY"])
            self.assertEqual("super-secret-token", captured["env"]["DASHSCOPE_API_KEY"])

    def test_write_summary_uses_existing_contract_without_mutating_task_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            out = package / "model_evaluation" / "qwen_eval"
            out.mkdir(parents=True)
            (package / "task.json").write_text(
                json.dumps({"metadata": {"model_evaluation": {}}}),
                encoding="utf-8",
            )
            results = [
                {"attempt": 1, "passed": True},
                {"attempt": 2, "passed": False, "error": "model patch is empty"},
            ]

            summary = eval_with_swe_agent.write_summary(package, out, "qwen_eval", "qwen3.6-flash",
                                                        "https://example.invalid/v1", results)

            self.assertEqual(summary["passes"], 1)
            self.assertEqual(summary["attempts"], 2)
            self.assertEqual(summary["pass_rate"], 0.5)
            self.assertTrue(summary["pass_nonzero"])
            self.assertTrue(summary["pass_rate_lte_50_percent"])
            self.assertEqual("[REDACTED_BASE_URL]", summary["base_url"])
            persisted = json.loads((out / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(persisted["requested_out_name"], "qwen_eval")
            self.assertEqual("[REDACTED_BASE_URL]", persisted["base_url"])
            task = json.loads((package / "task.json").read_text(encoding="utf-8"))
            self.assertEqual({}, task["metadata"]["model_evaluation"])
            self.assertEqual(1, persisted["status_counts"]["resolved"])
            self.assertEqual(1, persisted["status_counts"]["invalid"])

    def test_sanitized_dockerfile_removes_oracle_files(self) -> None:
        source = """FROM ubuntu:22.04
WORKDIR /workspace
COPY repo/ /workspace/repo/
COPY scripts/ /workspace/scripts/
COPY patches/ /workspace/patches/
COPY task.json problem_statement.md README.md verification.md /workspace/
RUN git config --global --add safe.directory /workspace/repo \\
    && cd /workspace/repo \\
    && go mod download \\
    && chmod +x /workspace/scripts/*.sh /workspace/scripts/parser.py
CMD ["bash", "/workspace/scripts/run_selected_tests.sh", "fixed"]
"""

        sanitized = eval_with_swe_agent.sanitized_swe_agent_dockerfile_text(source)

        self.assertIn("COPY repo/ /workspace/repo/", sanitized)
        self.assertNotIn("COPY patches/", sanitized)
        self.assertNotIn("COPY task.json", sanitized)
        self.assertNotIn("problem_statement.md", sanitized)
        self.assertNotIn("/workspace/scripts", sanitized)
        self.assertIn("&& go mod download", sanitized)
        self.assertNotIn("go mod download \\\nCMD", sanitized)
        self.assertIn('ENV PATH="/opt/swe-rex/bin:/usr/local/bin:/root/.local/bin:${PATH}"', sanitized)
        self.assertIn("/opt/swe-rex/bin/python -m pip install swe-rex", sanitized)
        self.assertIn("command -v swerex-remote", sanitized)
        self.assertIn('CMD ["bash"]', sanitized)

    def test_summary_counts_only_model_attempts_but_keeps_preflight_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            out = package / "model_evaluation" / "qwen_eval"
            out.mkdir(parents=True)
            (package / "task.json").write_text(
                json.dumps({"metadata": {"model_evaluation": {}}}),
                encoding="utf-8",
            )
            results = [
                {
                    "phase": "image_preflight",
                    "attempt": 0,
                    "passed": False,
                    "status": "test_infra_failed",
                    "error": "model-safe image preflight failed",
                },
            ]

            summary = eval_with_swe_agent.write_summary(package, out, "qwen_eval", "qwen3.6-flash",
                                                        "https://example.invalid/v1", results)

            self.assertEqual(0, summary["attempts"])
            self.assertEqual(0, summary["passes"])
            self.assertEqual(1, summary["status_counts"]["test_infra_failed"])
            self.assertFalse(summary["preflight_passed"])
            self.assertEqual(0, summary["model_calls_started"])

    def test_evaluation_artifacts_include_standard_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            run_dir = package / "model_evaluation" / "demo" / "run_01"
            run_dir.mkdir(parents=True)
            (package / "task.json").write_text(
                json.dumps({
                    "task_id": "instance_demo",
                    "repo": "acme/project",
                    "base_commit": "abc123",
                    "metadata": {"source_pr": "https://github.com/acme/project/pull/1"},
                    "evidence_links": ["https://github.com/acme/project/issues/2"],
                }),
                encoding="utf-8",
            )
            (run_dir / "eval.log").write_text("files_changed: src/app.py\n", encoding="utf-8")
            result = {
                "passed": False,
                "model_patch_applied": True,
                "test_patch_applied": True,
                "fail_to_pass_passed": True,
                "pass_to_pass_passed": False,
                "files_changed": ["src/app.py"],
            }

            eval_with_swe_agent.write_evaluation_artifacts(package, run_dir, result, "demo-model")

            test_output = json.loads((run_dir / "test_output.json").read_text(encoding="utf-8"))
            report = (run_dir / "evaluation_report.md").read_text(encoding="utf-8")
            self.assertEqual("partial", result["status"])
            self.assertEqual("partial", test_output["status"])
            self.assertIn("- conclusion: partial", report)
            self.assertIn("- pr_diff_hidden_from_model: true", report)

    def test_missing_api_key_error_is_infrastructure_failure(self) -> None:
        result = {
            "passed": False,
            "error": "OpenAIException - You didn't provide an API key. Authorization header using Bearer auth is required.",
        }

        self.assertEqual("test_infra_failed", eval_with_swe_agent.standard_status(result))

    def test_max_steps_schedule_repeats_last_value(self) -> None:
        schedule = eval_with_swe_agent.parse_max_steps_schedule("20,50,10", 20)

        self.assertEqual([20, 50, 10], schedule)
        self.assertEqual(20, eval_with_swe_agent.max_steps_for_attempt(schedule, 1))
        self.assertEqual(50, eval_with_swe_agent.max_steps_for_attempt(schedule, 2))
        self.assertEqual(10, eval_with_swe_agent.max_steps_for_attempt(schedule, 3))
        self.assertEqual(10, eval_with_swe_agent.max_steps_for_attempt(schedule, 4))
        self.assertEqual(10, eval_with_swe_agent.max_steps_for_attempt(schedule, 8))


if __name__ == "__main__":
    unittest.main()
