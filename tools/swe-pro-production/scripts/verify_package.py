#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, subprocess, sys
from pathlib import Path

MIN_GOLD_FILES = 5
MIN_GOLD_LINES = 108
PREFERRED_GOLD_LINES = 200
BLACKLIST_FILE_NAME = 'swe_existing_dataset_blacklist.xlsx'


def run(cmd: list[str], cwd: Path, allow_fail: bool = False) -> tuple[int, str]:
    p = subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if p.returncode and not allow_fail:
        raise RuntimeError(f'command failed {cmd}:\n{p.stdout}')
    return p.returncode, p.stdout


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding='utf-8'))


def check_task_json(root: Path, errors: list[str]) -> dict:
    path = root / 'task.json'
    if not path.exists():
        errors.append('missing task.json')
        return {}
    try:
        data = load_json(path)
    except Exception as e:
        errors.append(f'task.json invalid json: {e}')
        return {}
    required = ['repo','base_commit','problem_statement','patch','test_patch','fail_to_pass','pass_to_pass','requirements','interface','selected_test_files_to_run','before_repo_set_cmd','repo_language','issue_specificity','issue_categories']
    for k in required:
        if k not in data or data[k] in ('', [], None):
            errors.append(f'task.json missing/empty {k}')
    return data


def patch_stats(root: Path) -> dict:
    code, out = run(['git','-C',str(root/'repo'),'apply','--numstat',str(root/'patches/gold.patch')], root, allow_fail=True)
    files = add = dele = 0
    if code == 0:
        for line in out.splitlines():
            parts=line.split('\t')
            if len(parts) >= 3:
                try:
                    add += int(parts[0]); dele += int(parts[1]); files += 1
                except ValueError:
                    files += 1
    return {'files': files, 'additions': add, 'deletions': dele, 'total': add + dele}


def model_eval_summaries(root: Path, model_key: str) -> list[dict]:
    summaries: list[dict] = []
    eval_dir = root / 'model_evaluation'
    if not eval_dir.exists():
        return summaries
    for summary_path in eval_dir.glob('*/summary.json'):
        if model_key not in summary_path.parent.name.lower():
            continue
        try:
            data = load_json(summary_path)
        except Exception:
            continue
        data['_path'] = str(summary_path.relative_to(root))
        summaries.append(data)
    return summaries


def check_model_evaluation(root: Path, data: dict, errors: list[str], warnings: list[str]) -> None:
    metadata = data.get('metadata', {}).get('model_evaluation', {}) if data else {}
    opus_metadata = metadata.get('opus4_7_pass_at_8', '')
    qwen_metadata = metadata.get('qwen3_6_plus_pass_at_4', '') or metadata.get('qwen3_6_flash_pass_at_4', '')
    opus_summaries = model_eval_summaries(root, 'opus')
    qwen_summaries = model_eval_summaries(root, 'qwen')

    if not opus_metadata and not opus_summaries:
        errors.append('缺少 Opus 评测证据：需要先完成 Opus 4.7 pass@8 评测，并保留 model_evaluation/*opus*/summary.json；也可以把已有 Opus 结果同步到 task.json 的 metadata.model_evaluation.opus4_7_pass_at_8。')
    elif not opus_metadata:
        warnings.append('missing opus4.7 pass@8 metadata in task.json; using model_evaluation/*opus*/summary.json evidence')

    if not qwen_metadata and not qwen_summaries:
        errors.append('缺少 Qwen 评测证据：需要保留 model_evaluation/*qwen*/summary.json，或同步到 task.json 的 metadata.model_evaluation.qwen3_6_plus_pass_at_4。')
    elif not qwen_metadata:
        warnings.append('Qwen 评测证据已找到，但 task.json 还没有同步 metadata；当前先使用 model_evaluation/*qwen*/summary.json 作为证据。')


def check_delivery_blacklist(root: Path, errors: list[str]) -> None:
    path = root / BLACKLIST_FILE_NAME
    if not path.is_file():
        errors.append(f'missing delivery blacklist file next to task.json: {BLACKLIST_FILE_NAME}')
        return
    if path.stat().st_size == 0:
        errors.append(f'delivery blacklist file is empty: {BLACKLIST_FILE_NAME}')


def check_docker_validation(root: Path, errors: list[str]) -> None:
    validation_path = root / 'logs' / 'docker' / 'validation.json'
    if not validation_path.is_file():
        errors.append('missing Docker validation evidence: run package_task.py --docker before final packaging')
        return
    try:
        validation = load_json(validation_path)
    except Exception as e:
        errors.append(f'Docker validation evidence invalid json: {e}')
        return
    if not validation.get('ok'):
        reasons = validation.get('blocking_reasons') or []
        errors.append('Docker validation failed: ' + '; '.join(str(reason) for reason in reasons))
        return
    modes = validation.get('validation') or {}
    expected = {'baseline': 'fails', 'fixed': 'passes', 'pass-to-pass': 'passes'}
    for mode, expected_result in expected.items():
        result = modes.get(mode) if isinstance(modes, dict) else None
        if not isinstance(result, dict):
            errors.append(f'Docker validation missing mode: {mode}')
            continue
        if result.get('result') != expected_result:
            errors.append(f'Docker validation {mode} expected {expected_result}, got {result.get("result")}')


def main() -> int:
    ap = argparse.ArgumentParser(description='Verify a SWE-Pro task package locally.')
    ap.add_argument('package_dir')
    ap.add_argument('--run-tests', action='store_true')
    ap.add_argument('--validate-script', default='swe-pro-task-packager/scripts/validate_task_json.py')
    args = ap.parse_args()
    root = Path(args.package_dir).resolve()
    errors: list[str] = []
    warnings: list[str] = []
    data = check_task_json(root, errors)
    stats = patch_stats(root)
    if stats['files'] < MIN_GOLD_FILES or stats['total'] < MIN_GOLD_LINES:
        errors.append(
            f'gold.patch 不满足甲方规模要求：当前 {stats["files"]} 个文件、'
            f'+{stats["additions"]}/-{stats["deletions"]}、合计 {stats["total"]} 行。'
            f'当前最低要求是 gold/ground-truth patch 至少 {MIN_GOLD_FILES} 个文件、至少 {MIN_GOLD_LINES} 行；'
            'test.patch 是测试代码，不计入该规模要求。建议更换 gold.patch 更大的候选任务，或将该任务标记为不满足/备用。'
        )
    elif stats['total'] < PREFERRED_GOLD_LINES:
        warnings.append(
            f'gold.patch 已满足最低规模要求（至少 {MIN_GOLD_FILES} 个文件、{MIN_GOLD_LINES} 行），'
            f'但当前 {stats["total"]} 行低于推荐 {PREFERRED_GOLD_LINES} 行，建议作为有风险/备用包复核。'
        )
    check_docker_validation(root, errors)
    check_model_evaluation(root, data, errors, warnings)
    check_delivery_blacklist(root, errors)
    for rel in ['review/reviewer_1.md','review/reviewer_2.md','review/reviewer_3.md','review/adjudication_and_calibration.md']:
        if not (root / rel).exists():
            errors.append(f'missing review file: {rel}')
    if args.run_tests:
        code, out = run(['bash','scripts/verify_patch_application.sh'], root, allow_fail=True)
        if code != 0 or 'apply_checks_ok' not in out:
            errors.append('verify_patch_application failed')
        code, _ = run(['bash','scripts/run_selected_tests.sh','baseline'], root, allow_fail=True)
        if code == 0:
            errors.append('baseline unexpectedly passed')
        code, _ = run(['bash','scripts/run_selected_tests.sh','fixed'], root, allow_fail=True)
        if code != 0:
            errors.append('fixed did not pass')
        code, _ = run(['bash','scripts/run_selected_tests.sh','pass-to-pass'], root, allow_fail=True)
        if code != 0:
            errors.append('pass-to-pass did not pass')
    print(json.dumps({'package': str(root), 'patch_stats': stats, 'errors': errors, 'warnings': warnings, 'ok': not errors}, ensure_ascii=False, indent=2))
    return 1 if errors else 0

if __name__ == '__main__':
    raise SystemExit(main())
