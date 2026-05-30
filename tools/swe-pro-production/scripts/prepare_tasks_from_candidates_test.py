from __future__ import annotations

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

import prepare_tasks_from_candidates as prepare
import init_task_from_pr


class ReferencePackageHydrationTest(unittest.TestCase):
    def test_empty_before_cmd_uses_shell_noop_in_dockerfile(self) -> None:
        self.assertEqual("true", init_task_from_pr.shell_noop_if_empty(""))
        self.assertEqual("true", init_task_from_pr.shell_noop_if_empty("   "))
        self.assertEqual(
            "python -m pip install -e .",
            init_task_from_pr.shell_noop_if_empty("python -m pip install -e ."),
        )

    def make_oracle_package(self, root: Path) -> Path:
        package = root / "production-task-demo-7"
        repo = package / "repo"
        (package / "patches").mkdir(parents=True)
        (package / "scripts").mkdir()
        repo.mkdir(parents=True)
        (package / "scripts" / "run_selected_tests.sh").write_text("#!/usr/bin/env bash\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(repo), "init"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
        subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
        (repo / "value.txt").write_text("base\n", encoding="utf-8")
        (repo / "keep.txt").write_text("ok\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
        subprocess.run(["git", "-C", str(repo), "commit", "-m", "base"], check=True, stdout=subprocess.DEVNULL)
        (package / "patches" / "gold.patch").write_text(
            "diff --git a/value.txt b/value.txt\n"
            "--- a/value.txt\n"
            "+++ b/value.txt\n"
            "@@ -1 +1 @@\n"
            "-base\n"
            "+fixed\n",
            encoding="utf-8",
        )
        (package / "patches" / "test.patch").write_text(
            "diff --git a/hidden_test_marker.txt b/hidden_test_marker.txt\n"
            "new file mode 100644\n"
            "--- /dev/null\n"
            "+++ b/hidden_test_marker.txt\n"
            "@@ -0,0 +1 @@\n"
            "+marker\n",
            encoding="utf-8",
        )
        return package

    def test_problem_statement_uses_issue_text_not_fix_commit_or_links(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            package.mkdir(exist_ok=True)
            row = {
                "repo": "acme/demo",
                "pr_url": "https://github.com/acme/demo/pull/7",
                "issue_url": "https://github.com/acme/demo/issues/3",
                "base_commit": "base123",
                "fix_commit": "fix456",
                "problem_statement": "Login crashes on empty input.\n\nSteps to reproduce: submit the form without a username.",
                "hints_text": "Change src/auth.py to return early.",
            }

            prepare.write_problem_statement(package, row, ["tests/auth_test.py"], ["src/auth.py"])

            text = (package / "problem_statement.md").read_text(encoding="utf-8")
            self.assertTrue(text.startswith("# Login crashes on empty input."))
            self.assertIn("Steps to reproduce", text)
            self.assertNotIn("Source PR", text)
            self.assertNotIn("Issue:", text)
            self.assertNotIn("base123", text)
            self.assertNotIn("fix456", text)
            self.assertNotIn("Change src/auth.py", text)

    def test_javascript_package_uses_ubuntu_minimal_runtime_image(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            row = {
                "repo": "acme/demo",
                "pr_url": "https://github.com/acme/demo/pull/7",
                "base_commit": "base123",
                "fix_commit": "fix456",
                "primary_language": "javascript",
            }

            package = prepare.init_package(row, root, dry_run=False)

            dockerfile = (package / "dockerfiles" / "Dockerfile").read_text(encoding="utf-8")
            self.assertIn("FROM ubuntu:22.04", dockerfile)
            self.assertNotIn("FROM node:22-bookworm AS node_toolchain", dockerfile)
            self.assertNotIn("COPY --from=node_toolchain / /", dockerfile)
            self.assertIn("deb.nodesource.com/setup_22.x", dockerfile)
            self.assertIn("node --version", dockerfile)

    def test_imports_oracle_from_matching_reference_package(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            reference = root / "references" / "production-task-demo-7"
            package = root / "output" / "production-task-demo-7"
            (reference / "patches").mkdir(parents=True)
            (reference / "review").mkdir()
            (package / "patches").mkdir(parents=True)
            (package / "scripts").mkdir()
            (package / "review").mkdir()

            reference_task = {
                "repo": "acme/demo",
                "metadata": {"source_pr": "https://github.com/acme/demo/pull/7"},
                "issue_specificity": ["ui_ux_feat", "customization_feat"],
                "issue_categories": ["front_end_knowledge", "ui_ux_knowledge", "web_knowledge"],
                "test_patch": "diff --git a/tests/demo.test.js b/tests/demo.test.js\n",
                "fail_to_pass": ["npm test -- demo.test.js"],
                "pass_to_pass": ["npm run build"],
                "selected_test_files_to_run": ["tests/demo.test.js"],
                "interface": "Reference oracle",
                "requirements": "npm install",
            }
            (reference / "task.json").write_text(json.dumps(reference_task), encoding="utf-8")
            (reference / "patches" / "test.patch").write_text(reference_task["test_patch"], encoding="utf-8")
            (reference / "review" / "reviewer_1.md").write_text("approved", encoding="utf-8")
            (reference / "review" / "reviewer_2.md").write_text("approved", encoding="utf-8")
            (reference / "review" / "reviewer_3.md").write_text("approved", encoding="utf-8")
            (reference / "review" / "adjudication_and_calibration.md").write_text("approved", encoding="utf-8")
            (package / "task.json").write_text(
                json.dumps({"repo": "acme/demo", "before_repo_set_cmd": "go mod download"}),
                encoding="utf-8",
            )
            (package / "problem_statement.md").write_text("Add configurable homepage layout.", encoding="utf-8")
            (package / "patches" / "test.patch").write_text("", encoding="utf-8")
            (package / "scripts" / "run_selected_tests.sh").write_text("#!/usr/bin/env bash\n", encoding="utf-8")

            row = {"repo": "acme/demo", "pr_url": "https://github.com/acme/demo/pull/7"}
            found = prepare.find_reference_package(row, [root / "references"])
            self.assertEqual(reference, found)

            oracle = prepare.hydrate_from_reference_package(package, reference)

            self.assertEqual(["tests/demo.test.js"], oracle["test_files"])
            self.assertEqual(["ui_ux_feat", "customization_feat"], oracle["issue_specificity"])
            self.assertEqual(["front_end_knowledge", "ui_ux_knowledge", "web_knowledge"], oracle["issue_categories"])
            self.assertEqual(reference_task["test_patch"], (package / "patches" / "test.patch").read_text())
            self.assertIn("npm test -- demo.test.js", (package / "scripts" / "run_selected_tests.sh").read_text())
            self.assertEqual("approved", (package / "review" / "reviewer_1.md").read_text())

            prepare.update_task_json_stub(package, row, oracle["test_files"], oracle["assessment"], oracle)

            task = json.loads((package / "task.json").read_text(encoding="utf-8"))
            self.assertEqual(["ui_ux_feat", "customization_feat"], task["issue_specificity"])
            self.assertEqual(["front_end_knowledge", "ui_ux_knowledge", "web_knowledge"], task["issue_categories"])
            self.assertEqual(task["issue_specificity"], task["metadata"]["issue_specificity"])
            self.assertEqual(task["issue_categories"], task["metadata"]["issue_categories"])
            self.assertEqual("https://github.com/acme/demo", task["repo_url"])
            self.assertEqual("https://github.com/acme/demo/pull/7", task["source_url"])
            self.assertEqual("https://github.com/acme/demo/pull/7", task["metadata"]["source_pr"])

    def test_oracle_alignment_warns_on_hidden_version_without_problem_signal(self) -> None:
        test_patch = (
            "diff --git a/tests/test_initialize.py b/tests/test_initialize.py\n"
            "--- a/tests/test_initialize.py\n"
            "+++ b/tests/test_initialize.py\n"
            "@@ -1 +1 @@\n"
            "-assert response.version == \"1.3.0\"\n"
            "+assert response.version == \"1.3.1\"\n"
        )
        assessment = prepare.assess_test_patch(test_patch, ["tests/test_initialize.py"])
        row = {"problem_statement": "Continuing a saved session crashes with AttributeError."}

        alignment = prepare.assess_oracle_alignment(row, test_patch, assessment)

        self.assertTrue(alignment["ok"])
        self.assertEqual([], alignment["blocking_reasons"])
        self.assertTrue(any("version/release" in reason for reason in alignment["risks"]))

    def test_test_patch_assessment_flags_external_resource_dependencies(self) -> None:
        test_patch = (
            "diff --git a/test/sdk/test_elasticsearch_core.py b/test/sdk/test_elasticsearch_core.py\n"
            "--- /dev/null\n"
            "+++ b/test/sdk/test_elasticsearch_core.py\n"
            "@@ -0,0 +1,8 @@\n"
            "+from urllib.request import urlopen\n"
            "+from nexent.core.models.embedding_model import JinaEmbedding\n"
            "+def test_create_test_knowledge_base():\n"
            "+    es_client = ElasticSearchCore(host=\"http://localhost:9200\", api_key=None, embedding_model=JinaEmbedding())\n"
            "+    response = urlopen(\"https://example.com/articles.json\")\n"
            "+    assert response is not None\n"
        )

        assessment = prepare.assess_test_patch(test_patch, ["test/sdk/test_elasticsearch_core.py"])

        self.assertEqual("high", assessment["level"])
        self.assertTrue(assessment["requires_manual_review"])
        signals = assessment["metrics"]["external_resource_signals"]
        self.assertIn("network fetch", signals)
        self.assertIn("literal remote URL", signals)
        self.assertIn("local service endpoint", signals)
        self.assertIn("runtime API key", signals)

    def test_test_patch_assessment_flags_production_files_in_test_patch(self) -> None:
        test_patch = (
            "diff --git a/src/main/java/com/acme/Widget.java b/src/main/java/com/acme/Widget.java\n"
            "--- a/src/main/java/com/acme/Widget.java\n"
            "+++ b/src/main/java/com/acme/Widget.java\n"
            "@@ -1 +1 @@\n"
            "-class Widget {}\n"
            "+class Widget { boolean fixed() { return true; } }\n"
        )

        assessment = prepare.assess_test_patch(test_patch, ["src/main/java/com/acme/Widget.java"])

        self.assertEqual("high", assessment["level"])
        self.assertIn("src/main/java/com/acme/Widget.java", assessment["metrics"]["non_test_patch_paths"])

    def test_android_instrumentation_test_file_stays_in_test_patch(self) -> None:
        self.assertTrue(prepare.is_test_file("app/src/androidTest/java/de/test/antennapod/ui/DownloadLogTest.java"))
        self.assertTrue(prepare.is_test_file("src/test/kotlin/com/acme/ParserTest.kt"))

    def test_llm_oracle_review_rewrite_verdict_marks_assessment_high(self) -> None:
        assessment = {
            "level": "low",
            "risks": [],
            "strengths": [],
            "metrics": {},
            "requires_manual_review": False,
        }
        review = {
            "enabled": True,
            "status": "ok",
            "verdict": "rewrite",
            "fairness_summary": "test requires a gold-only UI button instead of the visible podcast title.",
            "rewrite_plan": ["Assert that the details dialog contains the podcast title."],
        }

        merged = prepare.merge_llm_oracle_review(assessment, review)

        self.assertEqual("high", merged["level"])
        self.assertTrue(merged["requires_manual_review"])
        self.assertEqual("ok", merged["metrics"]["llm_oracle_review_status"])
        self.assertTrue(any("rewrite" in risk for risk in merged["risks"]))

    def test_oracle_review_prompt_frames_gold_as_evidence_not_target(self) -> None:
        prompt = prepare.build_oracle_review_prompt(
            {"problem_statement": "Show the podcast name in download log details."},
            ["app/src/androidTest/java/de/test/antennapod/ui/DownloadLogTest.java"],
            ["app/src/main/java/de/danoeh/antennapod/ui/screen/download/DownloadLogDetailsDialog.java"],
            "diff --git a/src/Fix.java b/src/Fix.java\n+class Fix {}\n",
            "diff --git a/src/test/FixTest.java b/src/test/FixTest.java\n+assertVisible(\"Open\")\n",
        )

        self.assertIn("gold.patch is only evidence of one valid fix", prompt)
        self.assertIn("Return JSON only", prompt)
        self.assertIn("Show the podcast name", prompt)

    def test_pass_to_pass_uses_selected_go_package_for_mixed_language_repo(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-7"
            (package / "repo" / "middleware").mkdir(parents=True)
            (package / "scripts").mkdir()
            (package / "patches").mkdir()
            (package / "repo" / "go.mod").write_text("module demo\n", encoding="utf-8")
            (package / "repo" / "web" / "package.json").parent.mkdir()
            (package / "repo" / "web" / "package.json").write_text('{"scripts":{"test":"vitest"}}', encoding="utf-8")
            (package / "task.json").write_text(
                json.dumps({"repo": "acme/demo", "before_repo_set_cmd": "go mod download"}),
                encoding="utf-8",
            )
            (package / "problem_statement.md").write_text("Fix middleware auth.", encoding="utf-8")
            (package / "patches" / "gold.patch").write_text("", encoding="utf-8")
            (package / "patches" / "test.patch").write_text(
                "diff --git a/middleware/header_nav_test.go b/middleware/header_nav_test.go\n"
                "--- /dev/null\n"
                "+++ b/middleware/header_nav_test.go\n"
                "@@ -0,0 +1,3 @@\n"
                "+package middleware\n"
                "+func TestAuth(t *testing.T) { HeaderNavModuleAuth(\"rankings\") }\n"
                "+func TestPublic(t *testing.T) { HeaderNavModulePublicOrUserAuth(\"pricing\") }\n",
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text("#!/usr/bin/env bash\n", encoding="utf-8")

            row = {"repo": "acme/demo", "primary_language": "typescript", "problem_statement": "Rankings must obey header nav access settings."}
            prepare.update_task_json_stub(package, row, ["middleware/header_nav_test.go"], {"ok": True})

            task = json.loads((package / "task.json").read_text(encoding="utf-8"))
            script = (package / "scripts" / "run_selected_tests.sh").read_text(encoding="utf-8")
            self.assertEqual(["(go test ./middleware -count=1)"], task["pass_to_pass"])
            self.assertEqual("go", task["repo_language"])
            self.assertEqual("go mod download", task["before_repo_set_cmd"])
            self.assertNotIn("HeaderNavModuleAuth", task["interface"])
            self.assertNotIn("HeaderNavModulePublicOrUserAuth", task["interface"])
            self.assertNotIn("Helper", task["interface"])
            self.assertIn("Rankings must obey header nav access settings.", task["requirements"])
            self.assertNotIn("Fail-to-pass command", task["requirements"])
            self.assertNotIn("Selected test files", task["requirements"])
            self.assertIn("(go test ./middleware -count=1)", script)
            self.assertNotIn("npm test -- --runInBand", script)

    def test_python_pass_to_pass_prefers_lightweight_unmodified_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-7"
            (package / "repo" / "tests" / "acp").mkdir(parents=True)
            (package / "repo" / "tests" / "acp" / "test_initialize.py").write_text("", encoding="utf-8")
            (package / "repo" / "tests" / "acp" / "test_helpers.py").write_text("", encoding="utf-8")
            (package / "repo" / "tests" / "acp" / "test_model_training.py").write_text("", encoding="utf-8")
            (package / "repo" / "tests" / "test_order.py").write_text("", encoding="utf-8")

            command = prepare.pass_to_pass_command_for_files(
                package,
                {"primary_language": "python"},
                ["tests/acp/test_initialize.py"],
            )

            self.assertIn("python -m pytest", command)
            self.assertIn("tests/acp/test_helpers.py", command)
            self.assertIn("tests/test_order.py", command)
            self.assertNotIn("--ignore=", command)
            self.assertNotIn("test_initialize.py", command)
            self.assertNotIn("test_model_training.py", command)

    def test_javascript_pass_to_pass_uses_unmodified_test_files_not_whole_package(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-js"
            app = package / "repo" / "web"
            (app / "src").mkdir(parents=True)
            (app / "package.json").write_text('{"scripts":{"test":"vitest"}}', encoding="utf-8")
            (app / "src" / "widget.test.ts").write_text("", encoding="utf-8")
            (app / "src" / "format.test.ts").write_text("", encoding="utf-8")
            (app / "src" / "browser.e2e.test.ts").write_text("", encoding="utf-8")

            command = prepare.pass_to_pass_command_for_files(
                package,
                {"primary_language": "typescript"},
                ["web/src/widget.test.ts"],
            )

            self.assertIn("cd web && npm run test -- src/format.test.ts", command)
            self.assertNotIn("widget.test.ts", command)
            self.assertNotIn("browser.e2e.test.ts", command)
            self.assertNotEqual("(cd web && npm run test)", command)

    def test_java_pass_to_pass_uses_unmodified_test_classes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-java"
            repo = package / "repo"
            test_root = repo / "src" / "test" / "java" / "com" / "acme"
            test_root.mkdir(parents=True)
            (repo / "pom.xml").write_text("<project />", encoding="utf-8")
            (test_root / "WidgetTest.java").write_text("", encoding="utf-8")
            (test_root / "ParserTest.java").write_text("", encoding="utf-8")
            (test_root / "IntegrationTest.java").write_text("", encoding="utf-8")

            command = prepare.pass_to_pass_command_for_files(
                package,
                {"primary_language": "java"},
                ["src/test/java/com/acme/WidgetTest.java"],
            )

            self.assertIn("mvn test -Dtest=ParserTest", command)
            self.assertNotIn("WidgetTest", command)
            self.assertNotIn("IntegrationTest", command)

    def test_go_pass_to_pass_uses_run_filter_when_unmodified_tests_exist(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-go"
            repo = package / "repo"
            (repo / "middleware").mkdir(parents=True)
            (repo / "go.mod").write_text("module demo\n", encoding="utf-8")
            (repo / "middleware" / "auth_test.go").write_text(
                "package middleware\n"
                "import \"testing\"\n"
                "func TestAuth(t *testing.T) {}\n",
                encoding="utf-8",
            )
            (repo / "middleware" / "public_test.go").write_text(
                "package middleware\n"
                "import \"testing\"\n"
                "func TestPublic(t *testing.T) {}\n"
                "func TestGuest(t *testing.T) {}\n",
                encoding="utf-8",
            )

            command = prepare.pass_to_pass_command_for_files(
                package,
                {"primary_language": "go"},
                ["middleware/auth_test.go"],
            )

            self.assertIn("go test ./middleware -run", command)
            self.assertIn("TestPublic", command)
            self.assertIn("TestGuest", command)
            self.assertNotIn("TestAuth", command)

    def test_rust_integration_test_command_uses_cargo_package_and_test_target(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-12"
            crate = package / "repo" / "crates" / "chisel"
            (crate / "tests" / "it" / "repl").mkdir(parents=True)
            (crate / "Cargo.toml").write_text(
                '[package]\nname = "chisel"\nversion = "0.1.0"\n',
                encoding="utf-8",
            )
            test_files = ["crates/chisel/tests/it/repl/mod.rs"]

            fail_cmd = prepare.test_command_for_files(package, {"primary_language": "rust"}, test_files)
            pass_cmd = prepare.pass_to_pass_command_for_files(package, {"primary_language": "rust"}, test_files)

            self.assertEqual("(cargo test -p chisel --test it)", fail_cmd)
            self.assertEqual("(cargo test -p chisel)", pass_cmd)

    def test_rust_pass_to_pass_uses_unmodified_integration_targets_when_candidate_oracle_empty(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-windows-rs-3942"
            crate = package / "repo" / "crates" / "libs" / "rdl"
            tests = crate / "tests"
            tests.mkdir(parents=True)
            (crate / "Cargo.toml").write_text(
                '[package]\nname = "windows-rdl"\nversion = "0.0.0"\n',
                encoding="utf-8",
            )
            for name in [
                "attribute-from-reference",
                "nested",
                "path",
                "attribute-on-class",
                "attribute-on-interface",
                "struct",
            ]:
                (tests / f"{name}.rs").write_text(
                    "use windows_rdl::*;\n#[test]\npub fn parse() { assert!(true); }\n",
                    encoding="utf-8",
                )

            test_files = [
                "crates/libs/rdl/tests/attribute-from-reference.rs",
                "crates/libs/rdl/tests/complex-attribute-refs.rs",
                "crates/libs/rdl/tests/nested.rs",
                "crates/libs/rdl/tests/path.rs",
            ]

            pass_cmd = prepare.pass_to_pass_command_for_files(
                package,
                {"primary_language": "rust"},
                test_files,
            )

            self.assertIn("cargo test -p windows-rdl --test attribute-on-class parse", pass_cmd)
            self.assertIn("cargo test -p windows-rdl --test attribute-on-interface parse", pass_cmd)
            self.assertIn("cargo test -p windows-rdl --test struct parse", pass_cmd)
            self.assertNotIn("attribute-from-reference", pass_cmd)
            self.assertNotIn("complex-attribute-refs", pass_cmd)
            self.assertNotIn(" nested ", pass_cmd)
            self.assertNotIn(" path ", pass_cmd)

    def test_rust_integration_test_command_uses_patch_test_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-14"
            crate = package / "repo" / "crates" / "chisel"
            (crate / "tests" / "it" / "repl").mkdir(parents=True)
            (crate / "Cargo.toml").write_text(
                '[package]\nname = "chisel"\nversion = "0.1.0"\n',
                encoding="utf-8",
            )
            test_files = ["crates/chisel/tests/it/repl/mod.rs"]
            test_ids = [
                "crates/chisel/tests/it/repl/mod.rs::eval_tempo_chain_id_uses_tempo_executor",
                "crates/chisel/tests/it/repl/mod.rs::eval_tempo_network_uses_tempo_executor",
            ]

            fail_cmd = prepare.test_command_for_files(
                package,
                {"primary_language": "rust"},
                test_files,
                test_ids,
            )

            self.assertEqual(
                "(cargo test -p chisel --test it eval_tempo_chain_id_uses_tempo_executor) && "
                "(cargo test -p chisel --test it eval_tempo_network_uses_tempo_executor)",
                fail_cmd,
            )

    def test_rust_pass_to_pass_uses_existing_base_tests_not_patch_ids(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-15"
            crate = package / "repo" / "crates" / "chisel"
            test_dir = crate / "tests" / "it" / "repl"
            test_dir.mkdir(parents=True)
            (crate / "Cargo.toml").write_text(
                '[package]\nname = "chisel"\nversion = "0.1.0"\n',
                encoding="utf-8",
            )
            (test_dir / "mod.rs").write_text(
                """
macro_rules! repl_test {
    ($name:ident, $flags:expr, init = $init:expr, | $cmd:ident | $test:expr) => {};
    ($name:ident, | $cmd:ident | $test:expr) => {};
}

repl_test!(repl_help, |repl| {
    repl.expect("Chisel help");
});

repl_test!(cheatcodes_available, "", init = true, |repl| {
    repl.expect("Decimal: 0");
});

repl_test!(trailing_whitespace, |repl| {
    repl.expect("Decimal: 42");
});
""",
                encoding="utf-8",
            )
            test_files = ["crates/chisel/tests/it/repl/mod.rs"]
            test_ids = [
                "crates/chisel/tests/it/repl/mod.rs::eval_tempo_chain_id_uses_tempo_executor",
                "crates/chisel/tests/it/repl/mod.rs::eval_tempo_network_uses_tempo_executor",
            ]

            pass_cmd = prepare.pass_to_pass_command_for_files(
                package,
                {"primary_language": "rust"},
                test_files,
                test_ids,
            )

            self.assertIn("cargo test -p chisel --test it repl_help", pass_cmd)
            self.assertIn("cargo test -p chisel --test it trailing_whitespace", pass_cmd)
            self.assertNotIn("eval_tempo", pass_cmd)
            self.assertNotIn("cheatcodes_available", pass_cmd)

    def test_extract_test_ids_from_rust_macro_patch(self) -> None:
        patch = """diff --git a/crates/chisel/tests/it/repl/mod.rs b/crates/chisel/tests/it/repl/mod.rs
--- a/crates/chisel/tests/it/repl/mod.rs
+++ b/crates/chisel/tests/it/repl/mod.rs
@@ -1,2 +1,8 @@
+repl_test!(
+    eval_tempo_network_uses_tempo_executor,
+    "--network tempo eval address(0xfeEC).code.length",
+    |repl| {
+        repl.expect("Decimal: 1");
+    }
+);
"""

        self.assertEqual(
            ["crates/chisel/tests/it/repl/mod.rs::eval_tempo_network_uses_tempo_executor"],
            prepare.extract_test_ids_from_patch(patch),
        )

    def test_rust_unit_test_command_uses_module_filter_for_fail_to_pass(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-13"
            crate = package / "repo"
            (crate / "src" / "parser").mkdir(parents=True)
            (crate / "Cargo.toml").write_text(
                '[package]\nname = "demo-parser"\nversion = "0.1.0"\n',
                encoding="utf-8",
            )
            test_files = ["src/parser/tests.rs"]

            fail_cmd = prepare.test_command_for_files(package, {"primary_language": "rust"}, test_files)
            pass_cmd = prepare.pass_to_pass_command_for_files(package, {"primary_language": "rust"}, test_files)

            self.assertEqual("(cargo test -p demo-parser parser::tests)", fail_cmd)
            self.assertEqual("(cargo test -p demo-parser)", pass_cmd)

    def test_generate_patches_uses_raw_pr_diff_without_llm_filtering(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-9"
            repo = package / "repo"
            (package / "patches").mkdir(parents=True)
            repo.mkdir(parents=True)
            subprocess.run(["git", "-C", str(repo), "init"], check=True, stdout=subprocess.DEVNULL)
            subprocess.run(["git", "-C", str(repo), "config", "user.email", "test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(repo), "config", "user.name", "Test"], check=True)
            (repo / "src").mkdir()
            (repo / "tests").mkdir()
            (repo / "src" / "widget.py").write_text("success = False\n", encoding="utf-8")
            (repo / "tests" / "test_widget.py").write_text("assert not success\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-m", "base"], check=True, stdout=subprocess.DEVNULL)
            base = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
            (repo / "src" / "widget.py").write_text("success = True\n", encoding="utf-8")
            (repo / "tests" / "test_widget.py").write_text("assert success\n", encoding="utf-8")
            subprocess.run(["git", "-C", str(repo), "add", "."], check=True)
            subprocess.run(["git", "-C", str(repo), "commit", "-m", "fix"], check=True, stdout=subprocess.DEVNULL)
            fix = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()

            test_files, source_files = prepare.generate_patches(package, base, fix)

            self.assertEqual(["tests/test_widget.py"], test_files)
            self.assertEqual(["src/widget.py"], source_files)
            self.assertIn("src/widget.py", (package / "patches" / "gold.patch").read_text(encoding="utf-8"))
            self.assertIn("tests/test_widget.py", (package / "patches" / "test.patch").read_text(encoding="utf-8"))

    def test_update_task_json_writes_actual_patch_stats_separate_from_candidate_stats(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp) / "production-task-demo-11"
            (package / "patches").mkdir(parents=True)
            (package / "scripts").mkdir()
            (package / "repo").mkdir()
            (package / "problem_statement.md").write_text("Fix rendered status.", encoding="utf-8")
            (package / "scripts" / "run_selected_tests.sh").write_text("#!/usr/bin/env bash\n", encoding="utf-8")
            (package / "task.json").write_text(json.dumps({
                "repo": "acme/demo",
                "base_commit": "base",
                "issue_specificity": ["localized_bug"],
                "issue_categories": ["ui_ux_knowledge"],
            }), encoding="utf-8")
            (package / "patches" / "gold.patch").write_text(
                "diff --git a/src/status.py b/src/status.py\n"
                "--- a/src/status.py\n"
                "+++ b/src/status.py\n"
                "@@ -1 +1,2 @@\n"
                "-old\n"
                "+new\n"
                "+extra\n",
                encoding="utf-8",
            )
            (package / "patches" / "test.patch").write_text(
                "diff --git a/tests/test_status.py b/tests/test_status.py\n"
                "new file mode 100644\n"
                "--- /dev/null\n"
                "+++ b/tests/test_status.py\n"
                "@@ -0,0 +1,2 @@\n"
                "+def test_status():\n"
                "+    assert True\n",
                encoding="utf-8",
            )
            row = {
                "repo": "acme/demo",
                "base_commit": "base",
                "fix_commit": "fix",
                "primary_language": "python",
                "patch_files": "14",
                "gold_patch_files": "13",
                "gold_total_changed": "139",
                "test_patch_files": "1",
                "test_total_changed": "4",
                "problem_statement": "Fix rendered status.",
            }

            prepare.update_task_json_stub(package, row, ["tests/test_status.py"], prepare.assess_test_patch((package / "patches" / "test.patch").read_text(), ["tests/test_status.py"]))

            task = json.loads((package / "task.json").read_text(encoding="utf-8"))
            metadata = task["metadata"]
            self.assertEqual("13", metadata["candidate_pr_patch_stats"]["gold_patch_files"])
            self.assertEqual(1, metadata["patch_stats"]["gold_patch_files"])
            self.assertEqual(3, metadata["patch_stats"]["gold_total_changed"])
            self.assertEqual(1, metadata["patch_stats"]["test_patch_files"])
            self.assertEqual(2, metadata["patch_stats"]["test_total_changed"])
            self.assertEqual(["src/status.py"], metadata["patch_stats"]["gold_paths"])
            self.assertEqual(["tests/test_status.py"], metadata["patch_stats"]["test_paths"])


if __name__ == "__main__":
    unittest.main()
