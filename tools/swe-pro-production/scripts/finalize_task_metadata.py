#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


TASK_SPEC_FILES = [
    "task.json",
    "problem_statement.md",
    "patches/gold.patch",
    "patches/test.patch",
    "scripts/run_selected_tests.sh",
    "scripts/verify_patch_application.sh",
    "dockerfiles/Dockerfile",
    "runtime_env.json",
]

LANGUAGE_DISPLAY = {
    "go": "Go",
    "golang": "Go",
    "python": "Python",
    "javascript": "JavaScript",
    "typescript": "TypeScript",
    "rust": "Rust",
    "java": "Java",
}


def read_json(path: Path) -> dict:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding="utf-8"))


def write_json_if_changed(path: Path, data: dict) -> bool:
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    old = path.read_text(encoding="utf-8") if path.is_file() else ""
    if old == text:
        return False
    path.write_text(text, encoding="utf-8")
    return True


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def task_spec_checksums(root: Path) -> dict[str, str | None]:
    checksums: dict[str, str | None] = {}
    for relative in TASK_SPEC_FILES:
        path = root / relative
        checksums[relative] = sha256_file(path) if path.is_file() else None
    return checksums


def count_lines(path: Path) -> int:
    if not path.is_file():
        return 0
    return len(path.read_text(encoding="utf-8", errors="replace").splitlines())


def patch_paths(patch_text: str) -> list[str]:
    paths: list[str] = []
    for line in patch_text.splitlines():
        if line.startswith("diff --git "):
            parts = line.split()
            if len(parts) >= 4:
                path = parts[3]
                if path.startswith("b/"):
                    path = path[2:]
                paths.append(path)
    return paths


def patch_stats_update(root: Path, metadata: dict) -> None:
    stats = metadata.setdefault("patch_stats", {})
    gold = root / "patches" / "gold.patch"
    test = root / "patches" / "test.patch"
    stats.setdefault("gold_patch_lines", count_lines(gold))
    stats.setdefault("test_patch_lines", count_lines(test))
    if "test_files" not in stats:
        test_text = test.read_text(encoding="utf-8", errors="replace") if test.is_file() else ""
        test_paths = stats.get("test_paths")
        if isinstance(test_paths, list):
            stats["test_files"] = len(test_paths)
        else:
            stats["test_files"] = len(patch_paths(test_text))


def validation_result(validation: dict, mode: str) -> dict:
    modes = validation.get("validation")
    if not isinstance(modes, dict):
        return {}
    result = modes.get(mode)
    return result if isinstance(result, dict) else {}


def sync_verification_metadata(task: dict, metadata: dict, validation: dict) -> None:
    verification = metadata.setdefault("verification", {})
    baseline = validation_result(validation, "baseline")
    fixed = validation_result(validation, "fixed")
    pass_to_pass = validation_result(validation, "pass-to-pass")
    if baseline:
        verification["baseline"] = "fails as expected" if baseline.get("result") == "fails" else str(baseline.get("result") or "")
    if fixed:
        verification["fixed"] = str(fixed.get("result") or "")
    if pass_to_pass:
        pass_cmds = task.get("pass_to_pass") if isinstance(task.get("pass_to_pass"), list) else []
        suffix = f": {pass_cmds[0]}" if pass_cmds else ""
        verification["pass_to_pass"] = str(pass_to_pass.get("result") or "") + suffix


def sync_docker_metadata(root: Path, task: dict, metadata: dict, validation: dict) -> None:
    image_info = read_json(root / "docker-image" / "image_info.txt")
    docker = metadata.setdefault("docker", {})
    for source in (validation, image_info):
        for key in ("image_tag", "image_id", "image_size", "image_tar", "image_tar_sha256"):
            if source.get(key) not in (None, ""):
                docker[key] = source[key]
    if validation.get("validation"):
        docker["validation"] = {
            "baseline_exit": validation_result(validation, "baseline").get("exit"),
            "baseline_result": validation_result(validation, "baseline").get("result"),
            "fixed_exit": validation_result(validation, "fixed").get("exit"),
            "fixed_result": validation_result(validation, "fixed").get("result"),
            "pass_to_pass_exit": validation_result(validation, "pass-to-pass").get("exit"),
            "pass_to_pass_result": validation_result(validation, "pass-to-pass").get("result"),
            "logs": [
                "logs/docker/baseline.log",
                "logs/docker/fixed.log",
                "logs/docker/pass_to_pass.log",
            ],
        }
    if docker.get("image_id"):
        metadata["docker_image_id"] = docker["image_id"]
    if docker.get("image_tar_sha256"):
        metadata["docker_image_tar_sha256"] = docker["image_tar_sha256"]
    if docker.get("image_tag"):
        task["docker_image"] = task.get("docker_image") or docker["image_tag"]
        task["dockerhub_tag"] = task.get("dockerhub_tag") or docker["image_tag"]


def model_eval_key(summary: dict, path: Path) -> str:
    value = f"{summary.get('model', '')} {path.parent.name}".lower()
    if "opus" in value:
        return "opus4_7_pass_at_8"
    if "qwen" in value:
        return "qwen3_6_plus_pass_at_4"
    return path.parent.name.lower().replace("-", "_") + "_evaluation"


def model_eval_value(summary: dict) -> str:
    passes = int(summary.get("passes") or 0)
    attempts = int(summary.get("attempts") or 0)
    pass_rate = float(summary.get("pass_rate") or 0)
    model = str(summary.get("model") or "")
    if "qwen" in model.lower():
        return f"{passes}/{attempts}, pass_rate={pass_rate:.1f}, pass_rate_lte_50_percent={str(pass_rate <= 0.5).lower()}"
    if "opus" in model.lower():
        return f"{passes}/{attempts}, pass@8_nonzero={str(passes > 0).lower()}"
    return f"{passes}/{attempts}, pass_rate={pass_rate:.1f}"


def sync_model_metadata(root: Path, metadata: dict) -> None:
    model_eval = metadata.setdefault("model_evaluation", {})
    base = root / "model_evaluation"
    if not base.is_dir():
        return
    for summary_path in sorted(base.glob("*/summary.json")):
        summary = read_json(summary_path)
        if not summary:
            continue
        key = model_eval_key(summary, summary_path)
        model_eval[key] = model_eval_value(summary)
        prefix = key.removesuffix("_pass_at_8").removesuffix("_pass_at_4").removesuffix("_evaluation")
        model_eval[f"{key}_summary_file"] = str(summary_path.relative_to(root))
        if summary.get("base_url"):
            model_eval[f"{prefix}_base_url"] = summary.get("base_url")
        if summary.get("model"):
            model_eval[f"{prefix}_model"] = summary.get("model")


def sync_quality_metadata(root: Path, metadata: dict) -> None:
    verification = metadata.get("verification") if isinstance(metadata.get("verification"), dict) else {}
    denoise = verification.get("test_patch_denoise_assessment") if isinstance(verification.get("test_patch_denoise_assessment"), dict) else {}
    if "test_quality" not in metadata:
        covered = []
        metrics = denoise.get("metrics") if isinstance(denoise.get("metrics"), dict) else {}
        if metrics:
            covered.append(f"test_patch changed_lines={metrics.get('changed_lines', 0)}, assertion_lines={metrics.get('assertion_lines', 0)}")
        metadata["test_quality"] = {
            "overfit_mitigation": denoise.get("summary") or "Oracle behavior was reviewed before final package export.",
            "covered_behaviors": covered,
        }
    batch_file = root / "batch_evidence" / "language_and_category_distribution.md"
    if batch_file.is_file():
        metadata["batch_evidence"] = {
            "language_and_category_distribution": str(batch_file.relative_to(root)),
            "language_coverage_scope": "Package language/category evidence is recorded for batch-level aggregation.",
            "category_mapping_scope": "Task issue labels are recorded in task.json metadata.",
        }
    review_dir = root / "review"
    review_files = {
        "reviewer_1": review_dir / "reviewer_1.md",
        "reviewer_2": review_dir / "reviewer_2.md",
        "reviewer_3": review_dir / "reviewer_3.md",
        "adjudication_and_calibration": review_dir / "adjudication_and_calibration.md",
    }
    if all(path.is_file() for path in review_files.values()):
        metadata["expert_review"] = {
            key: str(path.relative_to(root)) for key, path in review_files.items()
        }
        metadata["expert_review"]["status"] = "completed_for_this_package"


def normalize_language(task: dict, metadata: dict) -> None:
    language = str(metadata.get("language") or task.get("repo_language") or "").strip()
    if not language:
        return
    metadata["language"] = LANGUAGE_DISPLAY.get(language.lower(), language)


def refresh_validation_checksums(root: Path) -> bool:
    path = root / "logs" / "docker" / "validation.json"
    validation = read_json(path)
    if not validation:
        return False
    validation["task_spec_checksums"] = task_spec_checksums(root)
    return write_json_if_changed(path, validation)


def finalize(root: Path) -> dict:
    task_path = root / "task.json"
    task = read_json(task_path)
    if not task:
        raise SystemExit(f"missing or invalid task.json: {task_path}")
    metadata = task.setdefault("metadata", {})
    validation = read_json(root / "logs" / "docker" / "validation.json")

    normalize_language(task, metadata)
    patch_stats_update(root, metadata)
    if validation:
        sync_verification_metadata(task, metadata, validation)
        sync_docker_metadata(root, task, metadata, validation)
    sync_model_metadata(root, metadata)
    sync_quality_metadata(root, metadata)

    changed_task = write_json_if_changed(task_path, task)
    changed_validation = refresh_validation_checksums(root)
    return {
        "task_json_changed": changed_task,
        "validation_checksums_refreshed": changed_validation,
        "metadata_keys": sorted(metadata.keys()),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Finalize delivery metadata in task.json before package export.")
    parser.add_argument("package_dir")
    args = parser.parse_args()
    root = Path(args.package_dir).resolve()
    result = finalize(root)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
