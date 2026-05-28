#!/usr/bin/env python3
from __future__ import annotations
import argparse, re, sys
from pathlib import Path

SECRET_PATTERNS = [
    re.compile(r'(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{20,}'),
    re.compile(r'DASHSCOPE_API_KEY\s*='),
    re.compile(r'Authorization:\s*Bearer\s+(?<![A-Za-z0-9_-])sk-[A-Za-z0-9_-]{8,}', re.I),
]
STALE_PATTERNS = [
    'summary_partial', 'Qwen is marked skipped', 'not claimed as pass',
    'Failed to set default locale', 'Could not change LC_CTYPE',
]
BAD_NAMES = {'.DS_Store'}
BAD_SUFFIXES = ('~', '.tmp', '.bak')
BAD_PARTIAL_NAMES = {'summary_partial.json'}


def is_unwanted_file(p: Path) -> bool:
    if p.name in BAD_NAMES or p.name.endswith(BAD_SUFFIXES):
        return True
    return p.name in BAD_PARTIAL_NAMES or p.name.endswith('.partial')


def is_evidence_log_path(rel: str) -> bool:
    return rel.startswith('model_evaluation/') or rel.startswith('logs/')


def scan(root: Path) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    skip_parts = {'.git', 'node_modules', 'dist', 'vendor'}
    for p in root.rglob('*'):
        if not p.is_file():
            continue
        rel_path = p.relative_to(root)
        rel = str(rel_path)
        if any(part in skip_parts for part in rel_path.parts):
            continue
        if is_unwanted_file(p):
            errors.append(f'发现不应交付的临时/备份文件：{rel}')
        if p.stat().st_size == 0 and is_evidence_log_path(rel):
            errors.append(f'发现空的证据/日志文件：{rel}。请重新生成有效证据，或点击清理删除无效空文件。')
        if p.stat().st_size > 10_000_000:
            continue
        try:
            text = p.read_text(encoding='utf-8', errors='ignore')
        except Exception:
            continue
        for pat in SECRET_PATTERNS:
            if pat.search(text):
                if rel.startswith('repo/') and pat.pattern.startswith('sk-'):
                    warnings.append(f'源码中发现疑似 sk-* 示例字符串，请确认不是私钥：{rel}')
                else:
                    errors.append(f'发现疑似密钥泄露：{rel} 命中规则 {pat.pattern}。请人工移除或脱敏。')
        for needle in STALE_PATTERNS:
            if needle in text:
                warnings.append(f'发现旧调试/中间状态文本：{rel} 包含 “{needle}”，请确认是否需要清理。')
    dockerignore = root / '.dockerignore'
    if not dockerignore.exists():
        errors.append('缺少根目录 .dockerignore。打包前需要排除大产物、日志和本地缓存。')
    else:
        d = dockerignore.read_text(errors='ignore')
        for needed in ['docker-image/', 'model_evaluation/', 'logs/', '*.tar.gz']:
            if needed not in d:
                warnings.append(f'.dockerignore 建议增加排除规则：{needed}')
        if 'repo/.git' in d:
            errors.append('.dockerignore 不能排除 repo/.git；测试脚本通常需要 git reset/apply。')
    return errors, warnings


def main() -> int:
    ap = argparse.ArgumentParser(description='Check package hygiene before delivery.')
    ap.add_argument('package_dir')
    args = ap.parse_args()
    root = Path(args.package_dir).resolve()
    errors, warnings = scan(root)
    for w in warnings:
        print('WARN:', w)
    for e in errors:
        print('ERROR:', e)
    print(f'清洁度检查完成：错误 {len(errors)} 个，警告 {len(warnings)} 个')
    return 1 if errors else 0

if __name__ == '__main__':
    raise SystemExit(main())
