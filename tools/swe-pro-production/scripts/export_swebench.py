#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def read_text_if_exists(path: Path) -> str:
    return path.read_text(encoding='utf-8') if path.is_file() else ''


def load_task(package: Path) -> dict[str, Any]:
    return json.loads((package / 'task.json').read_text(encoding='utf-8'))


def instance_id(package: Path, task: dict[str, Any]) -> str:
    value = task.get('instance_id') or task.get('task_id')
    return str(value) if value else package.name


def list_value(task: dict[str, Any], key: str) -> list[str]:
    value = task.get(key) or []
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str) and value.strip():
        return [value]
    return []


def build_instance(package: Path) -> dict[str, Any]:
    task = load_task(package)
    metadata = task.get('metadata') if isinstance(task.get('metadata'), dict) else {}
    return {
        'instance_id': instance_id(package, task),
        'repo': task.get('repo') or '',
        'base_commit': task.get('base_commit') or task.get('baseCommit') or '',
        'problem_statement': task.get('problem_statement') or read_text_if_exists(package / 'problem_statement.md'),
        'hints_text': task.get('hints_text') or '',
        'created_at': task.get('created_at') or '',
        'patch': task.get('patch') or read_text_if_exists(package / 'patches' / 'gold.patch'),
        'test_patch': task.get('test_patch') or read_text_if_exists(package / 'patches' / 'test.patch'),
        'FAIL_TO_PASS': list_value(task, 'fail_to_pass'),
        'PASS_TO_PASS': list_value(task, 'pass_to_pass'),
        'environment_setup_commit': task.get('environment_setup_commit') or '',
        'version': task.get('version') or '',
        'source_pr': metadata.get('source_pr') or task.get('source_pr') or task.get('source_url') or '',
    }


def summary_model(summary_path: Path) -> str:
    try:
        summary = json.loads(summary_path.read_text(encoding='utf-8'))
    except Exception:
        return summary_path.parent.name
    model = summary.get('model')
    if isinstance(model, str) and model.strip():
        return model
    return summary_path.parent.name


def run_sort_key(path: Path) -> tuple[int, str]:
    digits = ''.join(ch for ch in path.name if ch.isdigit())
    return (int(digits) if digits else 0, path.name)


def evaluation_sort_key(summary_path: Path) -> tuple[int, str]:
    name = summary_path.parent.name.lower()
    if 'qwen' in name:
        return (0, name)
    if 'opus' in name:
        return (1, name)
    return (2, name)


def build_predictions(package: Path, instance: dict[str, Any]) -> list[dict[str, str]]:
    root = package / 'model_evaluation'
    if not root.is_dir():
        return []
    predictions: list[dict[str, str]] = []
    for summary_path in sorted(root.glob('*/summary.json'), key=evaluation_sort_key):
        model = summary_model(summary_path)
        eval_dir = summary_path.parent
        for run_dir in sorted([path for path in eval_dir.iterdir() if path.is_dir()], key=run_sort_key):
            patch = read_text_if_exists(run_dir / 'model.patch')
            if not patch.strip():
                patch = read_text_if_exists(run_dir / 'candidate.patch')
            if not patch.strip():
                continue
            predictions.append({
                'instance_id': str(instance['instance_id']),
                'model_name_or_path': model,
                'model_patch': patch,
                'run_id': run_dir.name,
                'evaluation_dir': eval_dir.name,
            })
    return predictions


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in rows), encoding='utf-8')


def export_package(package: Path, out_dir: Path) -> dict[str, Path]:
    package = package.resolve()
    out_dir = out_dir.resolve()
    instance = build_instance(package)
    predictions = build_predictions(package, instance)
    dataset_path = out_dir / 'dataset.jsonl'
    predictions_path = out_dir / 'predictions.jsonl'
    manifest_path = out_dir / 'manifest.json'
    write_jsonl(dataset_path, [instance])
    write_jsonl(predictions_path, predictions)
    manifest = {
        'package': str(package),
        'dataset': str(dataset_path),
        'predictions': str(predictions_path),
        'instances': 1,
        'predictions_count': len(predictions),
        'format': 'swebench-compatible-jsonl',
    }
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return {'dataset': dataset_path, 'predictions': predictions_path, 'manifest': manifest_path}


def main() -> None:
    parser = argparse.ArgumentParser(description='Export a SWE-Pro task package as SWE-bench-compatible JSONL files.')
    parser.add_argument('package', type=Path)
    parser.add_argument('--out-dir', type=Path, default=None)
    args = parser.parse_args()
    out_dir = args.out_dir or args.package / 'swebench_export'
    result = export_package(args.package, out_dir)
    print(json.dumps({key: str(value) for key, value in result.items()}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
