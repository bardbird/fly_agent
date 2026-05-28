#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import socket
import subprocess
import sys
import tarfile
from datetime import datetime, timezone
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent

DELIVERY_EXCLUDE_DIRS = {
    '.git',
    '.hg',
    '.svn',
    'node_modules',
    '.venv',
    'venv',
    'env',
    '__pycache__',
    '.pytest_cache',
    '.mypy_cache',
    '.ruff_cache',
    '.tox',
    '.nox',
    '.gradle',
    'target',
    '.cargo',
}

DELIVERY_EXCLUDE_FILES = {
    '.DS_Store',
}

DOCKERIGNORE_TEXT = '''
.git
**/.git
**/.hg
**/.svn
repo/**/node_modules
repo/**/.venv
repo/**/venv
repo/**/env
repo/**/__pycache__
repo/**/.pytest_cache
repo/**/.mypy_cache
repo/**/.ruff_cache
repo/**/.tox
repo/**/.nox
repo/**/.gradle
repo/**/target
repo/**/.cargo
logs/**
model_evaluation/
docker-image/**
*.tar
*.tar.gz
*.sha256
.DS_Store
'''.strip() + '\n'

def run(cmd: list[str], cwd: Path, log_path: Path | None = None, allow_fail: bool = False, env: dict[str, str] | None = None) -> tuple[int, str]:
    p = subprocess.run(cmd, cwd=cwd, env=env, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if log_path:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(p.stdout, encoding='utf-8')
    if p.returncode and not allow_fail:
        raise RuntimeError(f'command failed: {" ".join(cmd)}\n{p.stdout}')
    return p.returncode, p.stdout


def local_proxy_url() -> str | None:
    for host, port in (('127.0.0.1', 7897),):
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return f'http://{host}:{port}'
        except OSError:
            continue
    return None


def docker_build_proxy_url() -> str | None:
    if not local_proxy_url():
        return None
    host = os.environ.get('DOCKER_BUILD_PROXY_HOST', 'host.docker.internal').strip() or 'host.docker.internal'
    port = os.environ.get('DOCKER_BUILD_PROXY_PORT', '7897').strip() or '7897'
    return f'http://{host}:{port}'


def docker_build_host_args() -> list[str]:
    if sys.platform.startswith('linux') and docker_build_proxy_url():
        return ['--add-host=host.docker.internal:host-gateway']
    return []


def docker_proxy_build_args() -> list[str]:
    proxy = docker_build_proxy_url()
    if not proxy:
        return []
    no_proxy = 'localhost,127.0.0.1,::1,host.docker.internal'
    args: list[str] = []
    for key, value in {
        'HTTP_PROXY': proxy,
        'HTTPS_PROXY': proxy,
        'ALL_PROXY': proxy,
        'NO_PROXY': no_proxy,
        'http_proxy': proxy,
        'https_proxy': proxy,
        'all_proxy': proxy,
        'no_proxy': no_proxy,
    }.items():
        args.extend(['--build-arg', f'{key}={value}'])
    return args


def docker_proxy_env() -> dict[str, str]:
    env = os.environ.copy()
    proxy = local_proxy_url()
    if not proxy:
        return env
    no_proxy = env.get('NO_PROXY') or env.get('no_proxy') or 'localhost,127.0.0.1,::1'
    for key in ('HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY', 'http_proxy', 'https_proxy', 'all_proxy'):
        env[key] = proxy
    env['NO_PROXY'] = no_proxy
    env['no_proxy'] = no_proxy
    return env


def docker_run_proxy_args() -> list[str]:
    proxy = docker_build_proxy_url()
    if not proxy:
        return []
    no_proxy = 'localhost,127.0.0.1,::1,host.docker.internal'
    args: list[str] = []
    if sys.platform.startswith('linux'):
        args.extend(['--add-host=host.docker.internal:host-gateway'])
    for key, value in {
        'HTTP_PROXY': proxy,
        'HTTPS_PROXY': proxy,
        'ALL_PROXY': proxy,
        'NO_PROXY': no_proxy,
        'http_proxy': proxy,
        'https_proxy': proxy,
        'all_proxy': proxy,
        'no_proxy': no_proxy,
    }.items():
        args.extend(['-e', f'{key}={value}'])
    return args


def load_task(root: Path) -> dict:
    path = root / 'task.json'
    if not path.exists():
        return {}
    return json.loads(path.read_text(encoding='utf-8'))


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def ensure_dockerignore(root: Path) -> None:
    path = root / '.dockerignore'
    existing = path.read_text(encoding='utf-8') if path.exists() else ''
    if existing == DOCKERIGNORE_TEXT:
        return
    path.write_text(DOCKERIGNORE_TEXT, encoding='utf-8')


def should_retry_docker_build_without_buildkit(output: str) -> bool:
    lower = (output or '').lower()
    return (
        'failed to solve with frontend dockerfile.v0' in lower
        or 'failed size validation' in lower
        or 'failed to create llb definition' in lower
    )


def docker_build(root: Path, image_tag: str, log_path: Path) -> None:
    cmd = ['docker', 'build', *docker_build_host_args(), *docker_proxy_build_args(), '-t', image_tag, '-f', 'dockerfiles/Dockerfile', '.']
    code, output = run(cmd, root, log_path, allow_fail=True, env=docker_proxy_env())
    if code == 0:
        return
    if should_retry_docker_build_without_buildkit(output):
        env = docker_proxy_env()
        env['DOCKER_BUILDKIT'] = '0'
        code, _ = run(cmd, root, log_path, allow_fail=True, env=env)
    if code != 0:
        raise RuntimeError(f'command failed: {" ".join(cmd)}\nsee {log_path}')


def excluded_from_delivery(path: Path) -> bool:
    parts = set(path.parts)
    if parts & DELIVERY_EXCLUDE_DIRS:
        return True
    return path.name in DELIVERY_EXCLUDE_FILES


def default_image_tag(root: Path, task: dict) -> str:
    for key in ('docker_image', 'dockerhub_tag'):
        value = task.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return f'local/{root.name}:latest'


def run_static_checks(root: Path, run_tests: bool) -> None:
    logs = root / 'logs' / 'packaging'
    verify_cmd = [sys.executable, str(SCRIPT_DIR / 'verify_package.py'), str(root)]
    if run_tests:
        verify_cmd.append('--run-tests')
    run(verify_cmd, root.parent, logs / 'verify_package.log')
    run([sys.executable, str(SCRIPT_DIR / 'check_delivery_hygiene.py'), str(root)], root.parent, logs / 'check_delivery_hygiene.log')


def run_docker_checks(root: Path, image_tag: str, save_image: bool) -> dict:
    logs = root / 'logs' / 'docker'
    ensure_dockerignore(root)
    docker_build(root, image_tag, root / 'logs' / 'docker_build.log')

    results: dict[str, object] = {'image_tag': image_tag, 'validation': {}, 'ok': True, 'blocking_reasons': []}
    expected = {'baseline': False, 'fixed': True, 'pass-to-pass': True}
    for mode, should_pass in expected.items():
        log_name = mode.replace('-', '_') + '.log'
        code, _ = run(
            ['docker', 'run', '--rm', *docker_run_proxy_args(), image_tag, 'bash', f'/workspace/scripts/run_selected_tests.sh', mode],
            root,
            logs / log_name,
            allow_fail=True,
        )
        results['validation'][mode] = {
            'exit': code,
            'result': 'passes' if code == 0 else 'fails',
            'expected': 'passes' if should_pass else 'fails',
        }
        if should_pass and code != 0:
            results['ok'] = False
            results['blocking_reasons'].append(f'docker {mode} did not pass; see {logs / log_name}')
        if not should_pass and code == 0:
            results['ok'] = False
            results['blocking_reasons'].append(f'docker {mode} unexpectedly passed; see {logs / log_name}')

    inspect_code, inspect_out = run(['docker', 'image', 'inspect', image_tag], root, allow_fail=True)
    if inspect_code == 0:
        info = json.loads(inspect_out)[0]
        results['image_id'] = info.get('Id', '')
        results['image_size'] = info.get('Size', 0)

    logs.mkdir(parents=True, exist_ok=True)
    (logs / 'validation.json').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if not results['ok']:
        raise RuntimeError('docker validation failed: ' + '; '.join(results['blocking_reasons']))

    if save_image:
        image_dir = root / 'docker-image'
        image_dir.mkdir(exist_ok=True)
        tar_path = image_dir / f'{root.name}-image.tar'
        run(['docker', 'save', '-o', str(tar_path), image_tag], root)
        digest = sha256_file(tar_path)
        (tar_path.with_suffix(tar_path.suffix + '.sha256')).write_text(f'{digest}  {tar_path.name}\n', encoding='utf-8')
        results['image_tar'] = str(tar_path.relative_to(root))
        results['image_tar_sha256'] = digest
        (image_dir / 'image_info.txt').write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    return results


def create_archive(root: Path, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    archive = out_dir / f'{root.name}.tar.gz'
    with tarfile.open(archive, 'w:gz') as tar:
        def filter_info(info: tarfile.TarInfo) -> tarfile.TarInfo | None:
            rel = Path(info.name).relative_to(root.name)
            return None if excluded_from_delivery(rel) else info
        tar.add(root, arcname=root.name, filter=filter_info)
    digest = sha256_file(archive)
    archive.with_suffix(archive.suffix + '.sha256').write_text(f'{digest}  {archive.name}\n', encoding='utf-8')
    return archive


def main() -> int:
    ap = argparse.ArgumentParser(description='Run final checks and package a SWE-Pro delivery directory.')
    ap.add_argument('package_dir')
    ap.add_argument('--run-tests', action='store_true', help='Run local baseline/fixed/pass-to-pass tests through verify_package.py.')
    ap.add_argument('--docker', action='store_true', help='Build image and run Docker baseline/fixed/pass-to-pass checks.')
    ap.add_argument('--save-image', action='store_true', help='Docker-save the image into docker-image/. Implies --docker.')
    ap.add_argument('--image-tag', default='')
    ap.add_argument('--archive', action='store_true', help='Create package tar.gz after checks.')
    ap.add_argument('--out-dir', default='')
    args = ap.parse_args()

    root = Path(args.package_dir).resolve()
    task = load_task(root)
    image_tag = args.image_tag or default_image_tag(root, task)
    ensure_dockerignore(root)
    if args.docker or args.save_image:
        run_docker_checks(root, image_tag, args.save_image)
    if args.archive or args.run_tests or not (args.docker or args.save_image):
        run_static_checks(root, args.run_tests)
    if args.archive:
        out_dir = Path(args.out_dir).resolve() if args.out_dir else root.parent
        archive = create_archive(root, out_dir)
        print(f'archive={archive}')
        print(f'sha256={sha256_file(archive)}')
    print(f'package_ok={root}')
    print(f'checked_at={datetime.now(timezone.utc).isoformat()}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
