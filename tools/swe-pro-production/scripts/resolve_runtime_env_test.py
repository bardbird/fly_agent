#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import tempfile
import unittest
import unittest.mock
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import package_task
import eval_with_swe_agent
import resolve_runtime_env
import verify_package


class ResolveRuntimeEnvTest(unittest.TestCase):
    def test_resolves_selected_monorepo_setup_commands(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo" / "core" / "ui" / "src").mkdir(parents=True)
            (package / "repo" / "api").mkdir(parents=True)
            (package / "scripts").mkdir()
            (package / "repo" / "core" / "ui" / "package.json").write_text("{}", encoding="utf-8")
            (package / "repo" / "core" / "ui" / "package-lock.json").write_text("{}", encoding="utf-8")
            (package / "repo" / "go.mod").write_text("module demo\n", encoding="utf-8")
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "repo": "acme/demo",
                        "base_commit": "abc123",
                        "repo_language": "go",
                        "before_repo_set_cmd": "go mod download",
                        "fail_to_pass": ["cd core/ui && npm test -- widget.test.tsx"],
                        "pass_to_pass": ["go test ./... -count=1"],
                        "selected_test_files_to_run": [
                            "core/ui/src/widget.test.tsx",
                            "api/router_test.go",
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text(
                "cd core/ui && npm test -- widget.test.tsx\ngo test ./... -count=1\n",
                encoding="utf-8",
            )

            runtime = resolve_runtime_env.resolve_runtime_env(package)

            self.assertIn("go", runtime["languages"])
            self.assertIn("typescript", runtime["languages"])
            self.assertIn("cd core/ui && npm ci --legacy-peer-deps", runtime["setup_commands"])
            self.assertIn("go mod download", runtime["setup_commands"])
            self.assertTrue(any("nodejs" in cmd for cmd in runtime["docker"]["dependency_commands"]))
            self.assertTrue(any("go.dev/dl" in cmd for cmd in runtime["docker"]["dependency_commands"]))

    def test_python_pyproject_without_setup_uses_non_editable_install(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo").mkdir()
            (package / "scripts").mkdir()
            (package / "repo" / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"0.1.0\"\n",
                encoding="utf-8",
            )
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "repo": "acme/demo",
                        "base_commit": "abc123",
                        "repo_language": "python",
                        "fail_to_pass": ["python -m pytest tests/test_demo.py"],
                        "pass_to_pass": ["python -m pytest tests/test_demo.py"],
                        "selected_test_files_to_run": ["tests/test_demo.py"],
                    }
                ),
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text(
                "python -m pytest tests/test_demo.py\n",
                encoding="utf-8",
            )

            runtime = resolve_runtime_env.resolve_runtime_env(package)

            self.assertIn("PIP_NO_CACHE_DIR=1 PIP_BREAK_SYSTEM_PACKAGES=1 python3 -m pip install .", runtime["setup_commands"])
            self.assertNotIn("pip install -e .", "\n".join(runtime["setup_commands"]))
            self.assertNotIn("--break-system-packages", "\n".join(runtime["setup_commands"]))

    def test_update_task_metadata_publishes_before_repo_set_cmd(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo" / "src" / "demo").mkdir(parents=True)
            (package / "scripts").mkdir()
            (package / "dockerfiles").mkdir()
            (package / "repo" / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"0.1.0\"\n",
                encoding="utf-8",
            )
            (package / "repo" / "src" / "demo" / "setup.py").write_text(
                "print('not a packaging manifest')\n",
                encoding="utf-8",
            )
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "repo": "acme/demo",
                        "base_commit": "abc123",
                        "repo_language": "python",
                        "before_repo_set_cmd": "true",
                        "requirements": "",
                        "fail_to_pass": ["python3 -m pytest tests/test_demo.py"],
                        "pass_to_pass": ["python3 -m pytest tests/test_demo.py"],
                        "selected_test_files_to_run": ["tests/test_demo.py"],
                    }
                ),
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text(
                "#!/usr/bin/env bash\nBEFORE_REPO_SET_CMD=true\nrun_before_repo_set_cmd() { eval \"$BEFORE_REPO_SET_CMD\"; }\n",
                encoding="utf-8",
            )
            (package / "dockerfiles" / "Dockerfile").write_text(
                """FROM python:3.11-slim-bookworm
RUN git config --global --add safe.directory /workspace/repo \\
    && cd /workspace/repo \\
    && if [ ! -d .git ]; then git init -q && git config user.email "fly-agent@example.invalid" && git config user.name "fly-agent" && git add -A && git commit -q -m "baseline snapshot"; fi \\
    && true \\
    && chmod +x /workspace/scripts/*.sh /workspace/scripts/parser.py
""",
                encoding="utf-8",
            )

            runtime = resolve_runtime_env.resolve_runtime_env(package)
            resolve_runtime_env.update_task_metadata(package, runtime)

            task = json.loads((package / "task.json").read_text(encoding="utf-8"))
            self.assertIn("python3 -m pip install .", task["before_repo_set_cmd"])
            self.assertEqual(task["before_repo_set_cmd"], task["requirements"])
            self.assertNotIn("src/demo", task["before_repo_set_cmd"])
            self.assertNotIn("((", task["before_repo_set_cmd"])
            self.assertIn("python3 -m pip install .", (package / "scripts" / "run_selected_tests.sh").read_text(encoding="utf-8"))
            dockerfile = (package / "dockerfiles" / "Dockerfile").read_text(encoding="utf-8")
            self.assertIn("FROM python:3.11-slim-bookworm", dockerfile)
            self.assertIn("python3 -m pip install .", dockerfile)
            self.assertIn("&& cd /workspace/repo", dockerfile)
            self.assertNotIn("((", dockerfile)

    def test_update_task_metadata_publishes_multiple_docker_toolchains(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo" / "frontend").mkdir(parents=True)
            (package / "repo" / "backend").mkdir()
            (package / "scripts").mkdir()
            (package / "dockerfiles").mkdir()
            (package / "repo" / "frontend" / "package.json").write_text("{}", encoding="utf-8")
            (package / "repo" / "backend" / "requirements.txt").write_text("pytest\n", encoding="utf-8")
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "repo": "acme/demo",
                        "base_commit": "abc123",
                        "repo_language": "python",
                        "fail_to_pass": ["python3 -m pytest test/sdk/test_demo.py"],
                        "pass_to_pass": ["python3 -m pytest test/sdk/test_demo.py"],
                        "selected_test_files_to_run": [
                            "frontend/src/App.test.tsx",
                            "test/sdk/test_demo.py",
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text(
                "#!/usr/bin/env bash\npython3 -m pytest test/sdk/test_demo.py\n",
                encoding="utf-8",
            )
            (package / "dockerfiles" / "Dockerfile").write_text(
                """FROM python:3.11-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash python3-dev build-essential && rm -rf /var/lib/apt/lists/*
RUN git config --global --add safe.directory /workspace/repo \\
    && cd /workspace/repo \\
    && if [ ! -d .git ]; then git init -q && git config user.email "fly-agent@example.invalid" && git config user.name "fly-agent" && git add -A && git commit -q -m "baseline snapshot"; fi \\
    && true \\
    && chmod +x /workspace/scripts/*.sh /workspace/scripts/parser.py
""",
                encoding="utf-8",
            )

            runtime = resolve_runtime_env.resolve_runtime_env(package)
            resolve_runtime_env.update_task_metadata(package, runtime)

            dockerfile = (package / "dockerfiles" / "Dockerfile").read_text(encoding="utf-8")
            self.assertIn("python3-dev", dockerfile)
            self.assertIn("pytest", dockerfile)
            self.assertIn("/root/.cache/pip", dockerfile)
            self.assertIn("deb.nodesource.com/setup_22.x", dockerfile)
            self.assertIn("nodejs", dockerfile)
            self.assertIn("npm --version", dockerfile)
            self.assertIn("npm cache clean --force", dockerfile)
            self.assertIn("/root/.npm", dockerfile)

    def test_generated_before_repo_set_cmd_is_not_readded_on_resume(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo" / "frontend").mkdir(parents=True)
            (package / "repo" / "backend").mkdir()
            (package / "scripts").mkdir()
            (package / "repo" / "frontend" / "package.json").write_text("{}", encoding="utf-8")
            (package / "repo" / "backend" / "requirements.txt").write_text("pytest\n", encoding="utf-8")
            generated_before = (
                "(cd frontend && npm install --legacy-peer-deps) && "
                "(cd backend && PIP_NO_CACHE_DIR=1 PIP_BREAK_SYSTEM_PACKAGES=1 python3 -m pip install -r requirements.txt) && "
                "(cd sdk && PIP_NO_CACHE_DIR=1 PIP_BREAK_SYSTEM_PACKAGES=1 python3 -m pip install .)"
            )
            (package / "repo" / "sdk").mkdir()
            (package / "repo" / "sdk" / "pyproject.toml").write_text(
                "[project]\nname = \"demo\"\nversion = \"0.1.0\"\n",
                encoding="utf-8",
            )
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "repo": "acme/demo",
                        "base_commit": "abc123",
                        "repo_language": "python",
                        "before_repo_set_cmd": generated_before,
                        "fail_to_pass": ["python3 -m pytest test/sdk/test_demo.py"],
                        "pass_to_pass": ["python3 -m pytest test/sdk/test_demo.py"],
                        "selected_test_files_to_run": [
                            "frontend/src/App.test.tsx",
                            "test/sdk/test_demo.py",
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text(
                f"#!/usr/bin/env bash\nBEFORE_REPO_SET_CMD={json.dumps(generated_before)}\n",
                encoding="utf-8",
            )

            runtime = resolve_runtime_env.resolve_runtime_env(package)
            setup = resolve_runtime_env.compose_setup_command(runtime["setup_commands"])

            self.assertEqual(3, len(runtime["setup_commands"]))
            self.assertEqual(1, setup.count("cd frontend && npm install --legacy-peer-deps"))
            self.assertEqual(1, setup.count("cd backend && PIP_NO_CACHE_DIR=1 PIP_BREAK_SYSTEM_PACKAGES=1 python3 -m pip install -r requirements.txt"))
            self.assertEqual(1, setup.count("cd sdk && PIP_NO_CACHE_DIR=1 PIP_BREAK_SYSTEM_PACKAGES=1 python3 -m pip install ."))
            self.assertNotIn("((", setup)

    def test_compose_setup_command_does_not_create_bash_arithmetic_groups(self) -> None:
        setup = resolve_runtime_env.compose_setup_command([
            "true",
            "(python3 -m pip install -e . || python3 -m pip install .)",
        ])

        self.assertNotIn("((", setup)
        self.assertIn("( { (python3 -m pip install -e . || python3 -m pip install .); } )", setup)

    def test_package_task_records_task_spec_checksums_for_validation_freshness(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            for relative in package_task.TASK_SPEC_FILES:
                path = package / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative + "\n", encoding="utf-8")

            checksums = package_task.task_spec_checksums(package)

            self.assertIn("runtime_env.json", checksums)
            self.assertIn("scripts/run_selected_tests.sh", checksums)
            self.assertTrue(checksums["runtime_env.json"])
            old = checksums["runtime_env.json"]
            (package / "runtime_env.json").write_text("changed\n", encoding="utf-8")
            self.assertNotEqual(old, package_task.task_spec_checksums(package)["runtime_env.json"])

    def test_package_task_rejects_pytest_file_level_fail_to_pass_when_node_ids_exist(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "patches").mkdir()
            (package / "scripts").mkdir()
            selected = ["tests/test_app.py::test_login", "tests/test_app.py::test_logout"]
            (package / "task.json").write_text(
                json.dumps({
                    "selected_test_ids_to_run": selected,
                    "fail_to_pass": ["python3 -m pytest tests/test_app.py"],
                    "metadata": {"oracle_breadth_justification": "small target set"},
                }),
                encoding="utf-8",
            )
            (package / "patches" / "test.patch").write_text(
                "diff --git a/tests/test_app.py b/tests/test_app.py\n"
                "--- a/tests/test_app.py\n"
                "+++ b/tests/test_app.py\n"
                "@@ -0,0 +1,2 @@\n"
                "+def test_login(): pass\n"
                "+def test_logout(): pass\n",
                encoding="utf-8",
            )
            (package / "scripts" / "run_selected_tests.sh").write_text(
                "python3 -m pytest tests/test_app.py\n",
                encoding="utf-8",
            )

            report = package_task.oracle_quality_report(package)

            self.assertFalse(report["ok"])
            self.assertTrue(any("node ids" in reason for reason in report["blocking_reasons"]))

    def test_package_task_rejects_too_many_fail_to_pass_targets_without_justification(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "patches").mkdir()
            (package / "scripts").mkdir()
            selected = [f"tests/test_app.py::test_case_{index}" for index in range(21)]
            command = "python3 -m pytest " + " ".join(selected)
            (package / "task.json").write_text(
                json.dumps({
                    "selected_test_ids_to_run": selected,
                    "fail_to_pass": [command],
                }),
                encoding="utf-8",
            )
            (package / "patches" / "test.patch").write_text("", encoding="utf-8")
            (package / "scripts" / "run_selected_tests.sh").write_text(command, encoding="utf-8")

            report = package_task.oracle_quality_report(package)

            self.assertFalse(report["ok"])
            self.assertTrue(any("prune" in reason for reason in report["blocking_reasons"]))

    def test_verify_package_rejects_stale_docker_validation(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            for relative in verify_package.TASK_SPEC_FILES:
                path = package / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(relative + "\n", encoding="utf-8")
            validation_path = package / "logs" / "docker" / "validation.json"
            validation_path.parent.mkdir(parents=True, exist_ok=True)
            validation_path.write_text(
                json.dumps(
                    {
                        "ok": True,
                        "validation": {
                            "baseline": {"result": "fails"},
                            "fixed": {"result": "passes"},
                            "pass-to-pass": {"result": "passes"},
                        },
                        "task_spec_checksums": verify_package.task_spec_checksums(package),
                    }
                ),
                encoding="utf-8",
            )
            (package / "runtime_env.json").write_text("changed\n", encoding="utf-8")
            errors: list[str] = []

            verify_package.check_docker_validation(package, errors)

            self.assertIn("Docker validation is stale for current task spec: runtime_env.json", errors)

    def test_package_task_accepts_existing_blacklist_when_reference_is_not_mounted(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / package_task.BLACKLIST_FILE_NAME).write_text("existing", encoding="utf-8")

            with unittest.mock.patch.object(package_task, "find_blacklist_reference",
                                            side_effect=RuntimeError("missing reference")):
                package_task.ensure_delivery_static_files(package)

            self.assertEqual("existing", (package / package_task.BLACKLIST_FILE_NAME).read_text(encoding="utf-8"))

    def test_package_task_does_not_mutate_dockerfile_from_runtime_env_contract(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo").mkdir()
            (package / "dockerfiles").mkdir()
            (package / "runtime_env.json").write_text(
                json.dumps(
                    {
                        "setup_commands": ["go mod download", "cd web && npm ci --legacy-peer-deps"],
                        "docker": {
                            "dependency_commands": [
                                resolve_runtime_env.GO_DEPENDENCIES_CMD,
                                resolve_runtime_env.NODE_DEPENDENCIES_CMD,
                            ]
                        },
                    }
                ),
                encoding="utf-8",
            )
            dockerfile = package / "dockerfiles" / "Dockerfile"
            dockerfile.write_text(
                """FROM ubuntu:22.04
WORKDIR /workspace
COPY repo/ /workspace/repo/
COPY task.json problem_statement.md README.md verification.md /workspace/
""",
                encoding="utf-8",
            )
            before = dockerfile.read_text(encoding="utf-8")

            self.assertFalse(hasattr(package_task, "normalize_dockerfile_toolchains"))
            self.assertEqual(before, dockerfile.read_text(encoding="utf-8"))

    def test_package_task_has_no_root_npm_install_repair_hook(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            package = Path(tmp)
            (package / "repo" / "web" / "default").mkdir(parents=True)
            (package / "repo" / "web" / "admin").mkdir(parents=True)
            (package / "dockerfiles").mkdir()
            (package / "repo" / "web" / "default" / "package.json").write_text("{}", encoding="utf-8")
            (package / "repo" / "web" / "admin" / "package.json").write_text("{}", encoding="utf-8")
            (package / "task.json").write_text(
                json.dumps(
                    {
                        "before_repo_set_cmd": "go mod download",
                        "fail_to_pass": ["go test ./middleware -count=1"],
                        "pass_to_pass": ["go test ./middleware -count=1"],
                    }
                ),
                encoding="utf-8",
            )
            dockerfile = package / "dockerfiles" / "Dockerfile"
            dockerfile.write_text(
                """FROM ubuntu:22.04
WORKDIR /workspace
COPY repo/ /workspace/repo/
RUN git config --global --add safe.directory /workspace/repo \\
    && cd /workspace/repo \\
    && npm install --legacy-peer-deps
""",
                encoding="utf-8",
            )
            before = dockerfile.read_text(encoding="utf-8")

            self.assertFalse(hasattr(package_task, "normalize_dockerfile_npm_install_dir"))
            self.assertEqual(before, dockerfile.read_text(encoding="utf-8"))

    def test_model_evaluation_uses_docker_validation_not_local_runtime_toolchain_path(self) -> None:
        source = Path(eval_with_swe_agent.__file__).read_text(encoding="utf-8")

        self.assertFalse(hasattr(eval_with_swe_agent, "repo_test_env"))
        self.assertFalse(hasattr(eval_with_swe_agent, "run_repo_shell"))
        self.assertIn("ensure_validation_docker_image", source)
        self.assertIn("run_docker_eval_phase", source)


if __name__ == "__main__":
    unittest.main()
