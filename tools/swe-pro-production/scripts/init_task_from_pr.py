#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, shutil, subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / 'templates' / 'task_package'


def copy_template(dst: Path) -> None:
    if dst.exists():
        raise SystemExit(f'target exists: {dst}')
    shutil.copytree(TEMPLATE, dst)
    for p in dst.rglob('*'):
        if p.is_file() and p.suffix == '.template':
            p.rename(p.with_suffix(''))


def replace_tokens(dst: Path, values: dict[str, str]) -> None:
    for p in dst.rglob('*'):
        if not p.is_file() or p.stat().st_size > 2_000_000:
            continue
        try:
            text = p.read_text(encoding='utf-8')
        except UnicodeDecodeError:
            continue
        for k, v in values.items():
            text = text.replace('{{' + k + '}}', v)
        p.write_text(text, encoding='utf-8')


def shell_noop_if_empty(command: str) -> str:
    return command.strip() or 'true'


def main() -> int:
    ap = argparse.ArgumentParser(description='Initialize a SWE-Pro task package from templates. This scaffolds files; repo checkout and test authoring remain manual.')
    ap.add_argument('--package-name', required=True)
    ap.add_argument('--repo', required=True, help='owner/name')
    ap.add_argument('--source-pr', required=True)
    ap.add_argument('--base-commit', required=True)
    ap.add_argument('--fix-commit', required=True)
    ap.add_argument('--language', required=True)
    ap.add_argument('--before-cmd', default='go mod download')
    ap.add_argument('--base-image', default='ubuntu:22.04')
    ap.add_argument('--language-dependencies-cmd', default='apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash && rm -rf /var/lib/apt/lists/*')
    ap.add_argument('--toolchain-stages', default='')
    ap.add_argument('--toolchain-copy', default='')
    ap.add_argument('--fail-cmd', default="go test ./... -count=1")
    ap.add_argument('--pass-cmd', default="go test ./... -count=1")
    ap.add_argument('--out-root', default='.')
    args = ap.parse_args()
    dst = Path(args.out_root).resolve() / args.package_name
    task_id = 'instance_' + args.repo.replace('/', '__').replace('-', '_') + '_' + args.package_name.replace('production-task-', '').replace('-', '_')
    values = {
        'PACKAGE_NAME': args.package_name,
        'REPO': args.repo,
        'SOURCE_PR': args.source_pr,
        'BASE_COMMIT': args.base_commit,
        'FIX_COMMIT': args.fix_commit,
        'BASE_IMAGE': args.base_image,
        'LANGUAGE_DEPENDENCIES_CMD': args.language_dependencies_cmd,
        'TOOLCHAIN_STAGES': args.toolchain_stages,
        'TOOLCHAIN_COPY': args.toolchain_copy,
        'BEFORE_REPO_SET_CMD': shell_noop_if_empty(args.before_cmd),
        'FAIL_TO_PASS_CMD': args.fail_cmd,
        'PASS_TO_PASS_CMD': args.pass_cmd,
        'TASK_ID': task_id,
        'REPO_LANGUAGE': args.language,
        'LANGUAGE_LABEL': args.language,
        'BASE_COMMIT_URL': f'https://github.com/{args.repo}/commit/{args.base_commit}',
        'FIX_COMMIT_URL': f'https://github.com/{args.repo}/commit/{args.fix_commit}',
        'PROBLEM_STATEMENT_JSON': 'TODO',
        'GOLD_PATCH_JSON': 'TODO',
        'TEST_PATCH_JSON': 'TODO',
        'FAIL_TO_PASS_JSON': '[]',
        'PASS_TO_PASS_JSON': '[]',
        'REQUIREMENTS': args.before_cmd,
        'INTERFACE': 'TODO',
        'SELECTED_TEST_FILES_JSON': '[]',
        'ISSUE_SPECIFICITY_JSON': '[]',
        'ISSUE_CATEGORIES_JSON': '[]',
        'EVIDENCE_LINKS_JSON': json.dumps([f'https://github.com/{args.repo}', args.source_pr, f'https://github.com/{args.repo}/commit/{args.base_commit}', f'https://github.com/{args.repo}/commit/{args.fix_commit}']),
    }
    copy_template(dst)
    replace_tokens(dst, values)
    (dst / 'repo').mkdir(exist_ok=True)
    (dst / 'logs' / 'docker').mkdir(parents=True, exist_ok=True)
    (dst / 'docker-image').mkdir(exist_ok=True)
    (dst / 'model_evaluation').mkdir(exist_ok=True)
    print(dst)
    print('Next: checkout repo at base commit into repo/, create patches/gold.patch and patches/test.patch, then fill task.json.')
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
