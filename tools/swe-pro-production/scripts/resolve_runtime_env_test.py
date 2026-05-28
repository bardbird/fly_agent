#!/usr/bin/env python3
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import package_task
import eval_with_swe_agent
import resolve_runtime_env


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
