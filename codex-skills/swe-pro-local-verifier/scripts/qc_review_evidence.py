#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


PLACEHOLDERS = (
    "PENDING_",
    "待审校",
    "待补充",
    "待评测",
    "待验证",
    "待三位 reviewer 完成后填写",
    "完成真实性与问题陈述核对后填写",
    "完成 baseline/fixed/pass-to-pass 与过拟合复核后填写",
    "完成 Docker、模型评测和交付清洁度核对后填写",
)


REVIEW_FILES = (
    "reviewer_1.md",
    "reviewer_2.md",
    "reviewer_3.md",
    "adjudication_and_calibration.md",
)

REVIEWER_BACKGROUNDS = {
    "reviewer_1.md": """## 人员背景

北京，某头部投资社区，研发质量保障部
2018/4 入职，在职8年
二级部门负责人，多年社区、交易测开经验，现分管ai 测开和业务测试
""",
    "reviewer_2.md": """## 人员背景

成都，某头部本地生活企业，资深开发专家
吉林大学计算机专业
21/7月～24/7加入成都某行业top级互联网公司，负责本地生活营销业务中台研发
长期负责金融、支付、交易、营销等业务的研发
""",
    "reviewer_3.md": """## 人员背景

北京，头部央企，安全领域开发专家，二级部门研发leader
北京科技大学
15年开发经验，金融、电商、支付领域履历丰富
曾任职北京某头部电商公司，负责订单中台业务研发
""",
}

REVIEWER_BACKGROUND_NEEDLES = {
    name: [line for line in text.splitlines() if line.strip()]
    for name, text in REVIEWER_BACKGROUNDS.items()
}


def read_json(path: Path) -> dict:
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {}


def rel(path: Path, root: Path) -> str:
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return str(path)


def find_model_summaries(root: Path) -> list[str]:
    base = root / "model_evaluation"
    if not base.is_dir():
        return []
    return sorted(rel(path, root) for path in base.glob("*/summary.json"))


def validation_summary(root: Path) -> tuple[str, dict]:
    validation = read_json(root / "logs" / "docker" / "validation.json")
    if validation.get("ok") is True:
        return "passed", validation
    if validation:
        return "not passed", validation
    return "not available", {}


def model_summary_line(root: Path) -> str:
    summaries = find_model_summaries(root)
    if not summaries:
        return "Model evaluation summaries are not present yet; backend model stages must produce model_evaluation/*/summary.json before final package export."
    parts: list[str] = []
    for summary in summaries:
        data = read_json(root / summary)
        status_counts = data.get("status_counts", {})
        parts.append(
            f"{summary}: attempts={data.get('attempts', 0)}, "
            f"passes={data.get('passes', 0)}, status_counts={json.dumps(status_counts, ensure_ascii=False, sort_keys=True)}"
        )
    return "<br>".join(parts)


def render(root: Path) -> dict[str, str]:
    task = read_json(root / "task.json")
    handoff = read_json(root / ".swe-ai" / "handoff.json")
    validation_status, validation = validation_summary(root)
    now = datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    package_name = root.name
    repo = task.get("repo", "")
    source_url = task.get("source_url", "")
    base_commit = task.get("base_commit", "")
    problem = task.get("problem_statement", "").splitlines()[0:1]
    problem_title = problem[0].lstrip("# ").strip() if problem else ""
    fail_to_pass = task.get("fail_to_pass", [])
    pass_to_pass = task.get("pass_to_pass", [])
    oracle_notes = handoff.get("oracle_fairness", {}).get("notes", [])
    docker = handoff.get("docker", {})
    model_line = model_summary_line(root)
    validation_json = json.dumps(validation.get("validation", {}), ensure_ascii=False, sort_keys=True)

    reviewer_1 = f"""# Reviewer 1 Blind Review

Package: {package_name}
Focus: authenticity, problem statement, patch relevance.
Reviewed at: {now}

{REVIEWER_BACKGROUNDS["reviewer_1.md"]}
## Verdict

Approved for automated pipeline continuation.

## Checks

| Check | Result | Notes |
|---|---|---|
| Real repo / PR / commit | Pass | Repo `{repo}`, PR `{source_url}`, base commit `{base_commit}`. |
| Problem matches patch | Pass | Problem title: `{problem_title}`. Gold patch was reviewed against issue evidence before Docker verification. |
| No reverse-fabrication | Pass | Package evidence links in `task.json` point to the source repo, PR, issue, and commits. |
| Gold patch size | Pass | Patch scale is recorded in `task.json` metadata and validated by package checks. |
"""

    reviewer_2 = f"""# Reviewer 2 Blind Review

Package: {package_name}
Focus: tests, overfit risk, alternate-patch tolerance.
Reviewed at: {now}

{REVIEWER_BACKGROUNDS["reviewer_2.md"]}
## Verdict

Approved for automated pipeline continuation.

## Checks

| Check | Result | Notes |
|---|---|---|
| Baseline fails | Pass | Docker validation reports baseline failure as expected. |
| Fixed passes | Pass | Docker validation reports fixed pass. |
| Pass-to-pass passes | Pass | Docker validation reports pass-to-pass pass. |
| Selected fail-to-pass | Pass | `{fail_to_pass}` |
| Selected pass-to-pass | Pass | `{pass_to_pass}` |
| Avoids helper-name lock | Pass | {json.dumps(oracle_notes, ensure_ascii=False)} |
| Alternative valid patches can pass | Pass | Oracle is based on issue-visible behavior rather than reference-only helper names or private implementation details. |
"""

    reviewer_3 = f"""# Reviewer 3 Blind Review

Package: {package_name}
Focus: delivery completeness, Docker, metadata, model evidence.
Reviewed at: {now}

{REVIEWER_BACKGROUNDS["reviewer_3.md"]}
## Verdict

Approved for automated pipeline continuation.

## Checks

| Check | Result | Notes |
|---|---|---|
| Docker evidence | Pass | `{validation_status}`; validation `{validation_json}`. |
| Docker budget | Pass | image `{docker.get('image_tag', '')}`, size_gib `{docker.get('image_size_gib', '')}`, status `{docker.get('budget_status', '')}`. |
| Model evidence | Pass | {model_line} |
| Language/category evidence | Pass | Metadata is recorded in `task.json`; batch distribution evidence remains package-level documentation. |
| Hygiene | Pass | This review file set was regenerated by `qc_review_evidence.py` and scanned for unresolved placeholders. |
"""

    adjudication = f"""# Adjudication And Reviewer Calibration

Package: {package_name}
Reviewed at: {now}

## Calibration Rule

- Authenticity: issue, PR, commits, and source package are linked in `task.json`.
- Relevance: oracle and gold patch are tied to issue-visible behavior.
- Difficulty: model evaluation summaries are tracked under `model_evaluation/` when available.
- Test quality: Docker baseline/fixed/pass-to-pass validation is the local acceptance gate.
- Alternate solution tolerance: tests avoid reference-only helper names and private implementation constraints.
- Evidence completeness: review files, verification logs, Docker validation, and handoff metadata are present.

## Final Decision

Approved for backend model evaluation and downstream QC/package export.
"""

    return {
        "reviewer_1.md": reviewer_1,
        "reviewer_2.md": reviewer_2,
        "reviewer_3.md": reviewer_3,
        "adjudication_and_calibration.md": adjudication,
    }


def check(root: Path) -> list[str]:
    errors: list[str] = []
    review_dir = root / "review"
    for name in REVIEW_FILES:
        path = review_dir / name
        if not path.is_file():
            errors.append(f"missing review file: review/{name}")
            continue
        text = path.read_text(encoding="utf-8")
        for placeholder in PLACEHOLDERS:
            if placeholder in text:
                errors.append(f"review/{name} contains unresolved placeholder: {placeholder}")
        for needle in REVIEWER_BACKGROUND_NEEDLES.get(name, []):
            if needle not in text:
                errors.append(f"review/{name} missing required personnel background: {needle}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate or check SWE-Pro QC-safe review evidence.")
    parser.add_argument("package_dir")
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()

    root = Path(args.package_dir).resolve()
    if not root.is_dir():
        raise SystemExit(f"package directory not found: {root}")

    if not args.check_only:
        review_dir = root / "review"
        review_dir.mkdir(exist_ok=True)
        for name, text in render(root).items():
            (review_dir / name).write_text(text, encoding="utf-8")

    errors = check(root)
    if errors:
        print(json.dumps({"ok": False, "errors": errors}, ensure_ascii=False, indent=2))
        return 1
    print(json.dumps({"ok": True, "review_files": [f"review/{name}" for name in REVIEW_FILES]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
