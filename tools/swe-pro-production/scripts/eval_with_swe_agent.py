#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path


def log(message: str) -> None:
    print(f"[swe-agent-eval {time.strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


MODEL_INPUT_FORBIDDEN_PATTERNS = (
    r'\btest_patch\b',
    r'\bgold(?:en)?\s+patch\b',
    r'\bgold\.patch\b',
    r'\bfail_to_pass\b',
    r'\bpass_to_pass\b',
    r'\bselected_test(?:s|_files_to_run)?\b',
    r'Fail-to-pass command:',
    r'Pass-to-pass command:',
    r'Selected test files:',
    r'Selected tests:',
    r'Generated test patch',
    r'Referenced by generated tests',
    r'Public interfaces exercised by the selected tests',
)

INFRASTRUCTURE_FAILURE_NEEDLES = (
    'go: command not found',
    'npm: command not found',
    'node: command not found',
    'yarn: command not found',
    'pnpm: command not found',
    'python: command not found',
    'python3: command not found',
    'pytest: command not found',
    'modulenotfounderror: no module named',
    'mvn: command not found',
    'gradle: command not found',
    'gradlew: command not found',
    'java: command not found',
    'cargo: command not found',
    'rustc: command not found',
    'gcc: command not found',
    'g++: command not found',
    'cmake: command not found',
    'make: command not found',
    'connection timed out',
    'temporary failure in name resolution',
    'proxyconnect tcp',
    'container process terminated',
    'swerex-remote: not found',
    'an executable named `swe-rex` is not provided',
    'failed to build oracle-free swe-agent docker image',
    'failed to build validation docker image',
    'model-safe image preflight failed',
    'swerex-remote preflight failed',
    'no such option: --break-system-packages',
    "you didn't provide an api key",
    'authorization header using bearer auth',
)

COST_LOG_PATTERNS = (
    re.compile(r'\bcost\b', flags=re.I),
    re.compile(r'\bper_instance_cost\b', flags=re.I),
    re.compile(r'\btotal_cost\b', flags=re.I),
)

SECRET_LOG_PATTERNS = (
    re.compile(r'sk-[A-Za-z0-9_-]{20,}'),
    re.compile(r'(Authorization:\s*Bearer\s+)sk-[A-Za-z0-9_-]{8,}', flags=re.I),
    re.compile(r'(DASHSCOPE_API_KEY\s*=\s*)\S+', flags=re.I),
)

TASK_SPEC_FILES = [
    'task.json',
    'problem_statement.md',
    'patches/gold.patch',
    'patches/test.patch',
    'scripts/run_selected_tests.sh',
    'scripts/verify_patch_application.sh',
    'dockerfiles/Dockerfile',
    'runtime_env.json',
]

VALIDATION_IMAGE_SPEC_FILES = [
    'task.json',
    'problem_statement.md',
    'patches/gold.patch',
    'patches/test.patch',
    'scripts/run_selected_tests.sh',
    'scripts/verify_patch_application.sh',
]

BASE_URL_LOG_PATTERNS = (
    re.compile(r'(--base-url\s+)\S+', flags=re.I),
    re.compile(r'(--base-url=)\S+', flags=re.I),
    re.compile(r'(--agent\.model\.api_base=)\S+', flags=re.I),
    re.compile(r'((?:base_url|baseUrl|api_base)\s*[:=]\s*)([\'"]?)https?://[^\'"\s,}]+', flags=re.I),
)

REDACTED_BASE_URL = '[REDACTED_BASE_URL]'

DOCKERIGNORE_TEXT = """\
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
"""

REPO_BUILD_ARTIFACT_DIRS = {
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
}

MODEL_SAFE_DOCKERIGNORE_TEXT = DOCKERIGNORE_TEXT + """\
patches/
task.json
problem_statement.md
README.md
verification.md
review/
batch_evidence/
"""

STANDARD_EVALUATION_STATUSES = {
    'resolved',
    'partial',
    'model_failed',
    'compile_error',
    'patch_apply_failed',
    'test_infra_failed',
    'invalid',
}

def redact_cost_log_lines(text: str) -> str:
    if not text:
        return ''
    kept = [
        line
        for line in text.splitlines()
        if not any(pattern.search(line) for pattern in COST_LOG_PATTERNS)
    ]
    return '\n'.join(kept) + ('\n' if kept and text.endswith('\n') else '')


def redact_secret_log_text(text: str) -> str:
    if not text:
        return ''
    redacted = text
    redacted = SECRET_LOG_PATTERNS[0].sub('[REDACTED_API_KEY]', redacted)
    redacted = SECRET_LOG_PATTERNS[1].sub(r'\1[REDACTED_API_KEY]', redacted)
    redacted = SECRET_LOG_PATTERNS[2].sub('[REDACTED_API_KEY_ENV]', redacted)
    redacted = BASE_URL_LOG_PATTERNS[0].sub(r'\1' + REDACTED_BASE_URL, redacted)
    redacted = BASE_URL_LOG_PATTERNS[1].sub(r'\1' + REDACTED_BASE_URL, redacted)
    redacted = BASE_URL_LOG_PATTERNS[2].sub(r'\1' + REDACTED_BASE_URL, redacted)
    redacted = BASE_URL_LOG_PATTERNS[3].sub(r'\1\2' + REDACTED_BASE_URL, redacted)
    return redacted


def redact_display_log_text(text: str) -> str:
    return redact_cost_log_lines(redact_secret_log_text(text))


def command_for_display(cmd: list[str]) -> str:
    hidden_prefixes = (
        '--agent.model.api_key=',
        '--agent.model.per_instance_cost_limit=',
        '--agent.model.total_cost_limit=',
    )
    redact_next = False
    visible = []
    for part in cmd:
        if any(part.startswith(prefix) for prefix in hidden_prefixes):
            continue
        if redact_next:
            visible.append(REDACTED_BASE_URL)
            redact_next = False
            continue
        if part == '--base-url':
            visible.append(part)
            redact_next = True
            continue
        if part.startswith('--base-url='):
            visible.append('--base-url=' + REDACTED_BASE_URL)
            continue
        if part.startswith('--agent.model.api_base='):
            visible.append('--agent.model.api_base=' + REDACTED_BASE_URL)
            continue
        visible.append(part)
    return ' '.join(visible)


def run(cmd: list[str], cwd: Path, env: dict[str, str] | None = None, timeout: int | None = None) -> tuple[int, str]:
    log(f"command start cwd={cwd} cmd={command_for_display(cmd)}")
    started = time.monotonic()
    completed = subprocess.run(
        cmd,
        cwd=cwd,
        env=env,
        timeout=timeout,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
    )
    output = completed.stdout or ''
    log(f"command done exit_code={completed.returncode} elapsed={time.monotonic() - started:.2f}s output_bytes={len(output.encode('utf-8'))}")
    display_output = redact_display_log_text(output)
    if display_output.strip():
        log("command output begin")
        for line in display_output.rstrip('\n').splitlines():
            print(f"[cmd] {line}", flush=True)
        log("command output end")
    return completed.returncode, output


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


def read_task(package: Path) -> dict:
    return json.loads((package / 'task.json').read_text(encoding='utf-8'))


def safe_model_image_name(image: str, package: Path, dockerfile_text: str) -> str:
    digest = hashlib.sha256(f'{image}\n{package.resolve()}\n{dockerfile_text}'.encode('utf-8')).hexdigest()[:16]
    return f'fly-agent-model-safe:{digest}'


def ensure_python_and_swerex_setup_lines() -> list[str]:
    return [
        'ENV PATH="/opt/swe-rex/bin:/usr/local/bin:/root/.local/bin:${PATH}"',
        (
            'RUN if command -v apt-get >/dev/null 2>&1; then apt-get update && apt-get install -y --no-install-recommends '
            'python3 python3-pip python3-venv ca-certificates && rm -rf /var/lib/apt/lists/*; fi '
            '&& python3 --version '
            '&& python3 -m venv /opt/swe-rex '
            '&& /opt/swe-rex/bin/python -m pip install --upgrade pip '
            '&& /opt/swe-rex/bin/python -m pip install swe-rex '
            '&& command -v swerex-remote '
            '&& test -x "$(command -v swerex-remote)"'
        ),
    ]


def sanitized_swe_agent_dockerfile_text(text: str) -> str:
    lines: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if '&& python3 -m pip install --break-system-packages -e . || python3 -m pip install -e .' in line:
            line = line.replace(
                '&& python3 -m pip install --break-system-packages -e . || python3 -m pip install -e .',
                '&& if [ -f setup.py ] || [ -f pyproject.toml ]; then (python3 -m pip install --break-system-packages -e . || python3 -m pip install -e .); fi'
            )
            stripped = line.strip()
        if stripped.startswith('COPY scripts/'):
            continue
        if stripped.startswith('COPY patches/'):
            continue
        if stripped.startswith('COPY task.json '):
            continue
        if stripped.startswith('COPY problem_statement.md '):
            continue
        if 'chmod +x /workspace/scripts/' in line:
            if lines and lines[-1].rstrip().endswith('\\'):
                lines[-1] = lines[-1].rstrip()[:-1].rstrip()
            continue
        if stripped.startswith('CMD '):
            continue
        lines.append(line)
        if stripped == 'WORKDIR /workspace':
            lines.extend(ensure_python_and_swerex_setup_lines())
    sanitized = '\n'.join(lines).rstrip() + '\n'
    if 'CMD ' not in sanitized:
        sanitized += '\nCMD ["bash"]\n'
    forbidden = ('COPY patches/', 'COPY task.json', 'COPY problem_statement.md', '/workspace/patches')
    if any(token in sanitized for token in forbidden):
        raise RuntimeError('sanitized SWE-agent Dockerfile still contains oracle COPY directives')
    return sanitized


def write_swe_agent_safe_dockerfile(package: Path) -> Path:
    dockerfile = package / 'dockerfiles' / 'Dockerfile'
    if not dockerfile.is_file():
        raise RuntimeError(f'Dockerfile missing for SWE-agent image: {dockerfile}')
    safe_path = package / 'dockerfiles' / 'Dockerfile.swe-agent-safe'
    safe_path.write_text(sanitized_swe_agent_dockerfile_text(dockerfile.read_text(encoding='utf-8')), encoding='utf-8')
    return safe_path


def build_model_safe_docker_image(package: Path, image: str) -> str:
    safe_dockerfile = write_swe_agent_safe_dockerfile(package)
    safe_text = safe_dockerfile.read_text(encoding='utf-8')
    safe_image = safe_model_image_name(str(image), package, safe_text)
    code, _ = run(['docker', 'image', 'inspect', safe_image], package)
    if code == 0:
        return safe_image

    dockerignore = package / '.dockerignore'
    original_dockerignore = dockerignore.read_text(encoding='utf-8') if dockerignore.exists() else None
    dockerignore.write_text(MODEL_SAFE_DOCKERIGNORE_TEXT, encoding='utf-8')
    build_cmd = [
        'docker', 'build',
        *docker_build_host_args(),
        *docker_proxy_build_args(),
        '-t', safe_image,
        '-f', str(safe_dockerfile.relative_to(package)),
        '.',
    ]
    try:
        code, output = run(build_cmd, package, env=docker_proxy_env())
        if code != 0 and should_retry_docker_build_without_buildkit(output):
            env = docker_proxy_env()
            env['DOCKER_BUILDKIT'] = '0'
            code, output = run(build_cmd, package, env=env)
        if code != 0:
            raise RuntimeError('failed to build oracle-free SWE-agent docker image: ' + output[-2000:])
    finally:
        if original_dockerignore is None:
            dockerignore.unlink(missing_ok=True)
        else:
            dockerignore.write_text(original_dockerignore, encoding='utf-8')
    return safe_image


def validation_image_name(package: Path) -> str:
    h = hashlib.sha256()
    h.update(f'{package.resolve()}\n'.encode('utf-8'))
    for relative in TASK_SPEC_FILES:
        path = package / relative
        h.update(relative.encode('utf-8') + b'\0')
        if path.is_file():
            h.update(path.read_bytes())
        h.update(b'\0')
    digest = h.hexdigest()[:16]
    return f'local/swe-pro-validation-{digest}:latest'


def file_sha256(path: Path) -> str | None:
    if not path.is_file():
        return None
    return hashlib.sha256(path.read_bytes()).hexdigest()


def task_spec_checksums(package: Path) -> dict[str, str | None]:
    return {relative: file_sha256(package / relative) for relative in TASK_SPEC_FILES}


def ensure_validation_docker_image(package: Path) -> str:
    dockerfile = package / 'dockerfiles' / 'Dockerfile'
    if not dockerfile.is_file():
        raise RuntimeError(f'Dockerfile missing for validation image: {dockerfile}')
    ensure_dockerignore(package)
    image = validation_image_name(package)
    code, _ = run(['docker', 'image', 'inspect', image], package)
    if code == 0:
        return image
    build_cmd = [
        'docker', 'build',
        *docker_build_host_args(),
        *docker_proxy_build_args(),
        '-t', image,
        '-f', 'dockerfiles/Dockerfile',
        '.',
    ]
    code, output = run(build_cmd, package, env=docker_proxy_env())
    if code != 0 and should_retry_docker_build_without_buildkit(output):
        env = docker_proxy_env()
        env['DOCKER_BUILDKIT'] = '0'
        code, output = run(build_cmd, package, env=env)
    if code != 0:
        raise RuntimeError('failed to build validation docker image: ' + output[-2000:])
    return image


def validation_image_task_spec_checksums(package: Path, image: str) -> dict[str, str | None]:
    script_lines = ['cd /workspace || exit 97']
    for relative in VALIDATION_IMAGE_SPEC_FILES:
        quoted = shlex.quote(relative)
        script_lines.append(
            f'if [ -f {quoted} ]; then sha256sum {quoted}; else printf "MISSING  %s\\n" {quoted}; fi'
        )
    code, output = run(
        [
            'docker', 'run', '--rm',
            *docker_run_proxy_args(),
            image,
            'bash',
            '-lc',
            '\n'.join(script_lines),
        ],
        package,
        env=docker_proxy_env(),
    )
    if code != 0:
        raise RuntimeError('validation image task-spec checksum preflight failed: ' + output[-1200:])
    checksums: dict[str, str | None] = {}
    for line in output.splitlines():
        parts = line.strip().split(None, 1)
        if len(parts) != 2:
            continue
        digest, relative = parts
        if relative in VALIDATION_IMAGE_SPEC_FILES:
            checksums[relative] = None if digest == 'MISSING' else digest
    return {relative: checksums.get(relative) for relative in VALIDATION_IMAGE_SPEC_FILES}


def verify_validation_image_task_specs(package: Path, out: Path, image: str) -> dict:
    expected = task_spec_checksums(package)
    actual = validation_image_task_spec_checksums(package, image)
    mismatches = {
        relative: {
            'package': expected.get(relative),
            'image': actual.get(relative),
        }
        for relative in TASK_SPEC_FILES
        if relative in VALIDATION_IMAGE_SPEC_FILES
        if expected.get(relative) != actual.get(relative)
    }
    result = {
        'image': image,
        'passed': not mismatches,
        'package_checksums': expected,
        'image_checksums': actual,
        'mismatches': mismatches,
    }
    preflight_dir = out / 'validation_image_preflight'
    preflight_dir.mkdir(parents=True, exist_ok=True)
    (preflight_dir / 'task_spec_checksums.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n',
        encoding='utf-8',
    )
    if mismatches:
        raise RuntimeError(
            'validation image task-spec checksum mismatch: '
            + ', '.join(sorted(mismatches))
        )
    return result


def ensure_dockerignore(package: Path) -> None:
    path = package / '.dockerignore'
    if not path.exists() or path.read_text(encoding='utf-8', errors='ignore') != DOCKERIGNORE_TEXT:
        path.write_text(DOCKERIGNORE_TEXT, encoding='utf-8')


def ensure_swe_agent_docker_image(package: Path, task: dict | None = None) -> str | None:
    task = task or read_task(package)
    image = task.get('docker_image') or task.get('dockerhub_tag')
    if not image:
        return None
    dockerfile = package / 'dockerfiles' / 'Dockerfile'
    if not dockerfile.is_file():
        raise RuntimeError(f'Dockerfile missing for SWE-agent image: {dockerfile}')
    ensure_dockerignore(package)
    return build_model_safe_docker_image(package, str(image))


def artifact_path(package: Path, path: Path | None) -> str:
    if path is None:
        return ''
    try:
        return str(path.relative_to(package))
    except ValueError:
        return str(path)


def safe_artifact_name(value: str) -> str:
    return re.sub(r'[^A-Za-z0-9_.-]+', '_', value).strip('_') or 'image'


def model_safe_image_preflight_dir(package: Path, safe_image: str) -> Path:
    return package / 'model_evaluation' / '_image_preflight' / safe_artifact_name(safe_image)


def model_safe_image_preflight_script() -> str:
    return """set -eu
echo "preflight: image=$(cat /etc/hostname)"
echo "preflight: PATH=$PATH"
command -v python3
python3 --version
command -v swerex-remote
test -x "$(command -v swerex-remote)"
set +e
swerex-remote --help >/tmp/swerex-help.log 2>&1
help_rc=$?
set -e
echo "preflight: swerex-remote --help rc=$help_rc"
cat /tmp/swerex-help.log || true
if command -v timeout >/dev/null 2>&1; then
  set +e
  timeout 8s swerex-remote --auth-token preflight-token >/tmp/swerex-start.log 2>&1
  start_rc=$?
  set -e
  echo "preflight: swerex-remote start rc=$start_rc"
  cat /tmp/swerex-start.log || true
  if [ "$start_rc" -ne 0 ] && [ "$start_rc" -ne 124 ]; then
    exit "$start_rc"
  fi
else
  echo "preflight: timeout command missing, skipped server start smoke test"
fi
"""


def preflight_model_safe_image(package: Path, safe_image: str | None) -> dict:
    if not safe_image:
        return {'safe_image': '', 'passed': True, 'skipped': True}
    preflight_dir = model_safe_image_preflight_dir(package, safe_image)
    preflight_dir.mkdir(parents=True, exist_ok=True)
    log_path = preflight_dir / 'preflight.log'
    summary_path = preflight_dir / 'preflight.json'
    cmd = [
        'docker', 'run', '--rm',
        safe_image,
        '/bin/sh',
        '-c',
        model_safe_image_preflight_script(),
    ]
    code, output = run(cmd, package, env=docker_proxy_env(), timeout=45)
    log_path.write_text(redact_display_log_text(output), encoding='utf-8')
    summary = {
        'safe_image': safe_image,
        'passed': code == 0,
        'exit_code': code,
        'log_path': artifact_path(package, log_path),
        'summary_path': artifact_path(package, summary_path),
    }
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    if code != 0:
        raise RuntimeError(
            'model-safe image preflight failed: '
            f'safe_image={safe_image}, log={artifact_path(package, log_path)}, output_tail={output[-1200:]}'
        )
    return summary


def should_retry_docker_build_without_buildkit(output: str) -> bool:
    lower = (output or '').lower()
    return (
        'failed to solve with frontend dockerfile.v0' in lower
        or 'failed size validation' in lower
        or 'failed to create llb definition' in lower
    )


def contains_forbidden_model_input(text: str) -> bool:
    return any(re.search(pattern, text or '', flags=re.I) for pattern in MODEL_INPUT_FORBIDDEN_PATTERNS)


def remove_section(text: str, heading: str) -> str:
    pattern = re.compile(rf'\n## {re.escape(heading)}\n.*?(?=\n## |\Z)', flags=re.S)
    return pattern.sub('\n', text)


def sanitize_model_input_text(text: str) -> str:
    text = remove_section(text, 'Background')
    for heading in ['Test Coverage', 'Constraints', 'Requirements', 'Interface']:
        text = remove_section(text, heading)
    cleaned: list[str] = []
    skip_prefixes = (
        'Source PR:', 'Issue:', 'Repository:', 'Base commit:', 'Fix commit:',
        'Fail-to-pass command:', 'Pass-to-pass command:', 'Selected test files:',
        'Selected tests:',
    )
    for line in text.splitlines():
        if line.startswith(skip_prefixes):
            continue
        cleaned.append(line)
    return '\n'.join(cleaned).strip()


def write_swe_agent_problem_statement(package: Path, max_steps: int = 20, task: dict | None = None) -> str:
    task = task or read_task(package)
    source = ''
    problem_file = package / 'problem_statement.md'
    if problem_file.is_file():
        source = problem_file.read_text(encoding='utf-8')
    if not source.strip():
        source = str(task.get('problem_statement') or '')
    text = sanitize_model_input_text(source)
    if contains_forbidden_model_input(text):
        raise RuntimeError('problem statement contains forbidden oracle or patch metadata')
    text = text.rstrip() + '\n'
    src_dir = package / 'repo' / 'src'
    if src_dir.is_dir():
        packages = sorted(path.name for path in src_dir.iterdir() if path.is_dir() and not path.name.startswith('.'))
        if packages:
            text = (
                text.rstrip()
                + '\n\n## Repository Orientation\n\n'
                + 'The current repository source package directories under `src/` are: '
                + ', '.join(f'`{name}`' for name in packages)
                + '. Inspect these actual files before editing; do not create or modify invented package paths.\n'
            )
    if contains_forbidden_model_input(text):
        raise RuntimeError('generated SWE-agent problem statement contains forbidden oracle or patch metadata')
    out = package / 'model_evaluation' / '_swe_agent_problem_statement.md'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text.rstrip() + '\n', encoding='utf-8')
    return text


def looks_like_patch(text: str) -> bool:
    return (
        'diff --git ' in text
        or ('--- a/' in text and '+++ b/' in text and '@@' in text)
        or ('--- ' in text and '+++ ' in text and '@@' in text)
    )


def ensure_final_newline(text: str) -> str:
    return text if text.endswith('\n') else text + '\n'


def extract_unified_diff(text: str) -> str:
    fence_pattern = re.compile(r'```(?:diff|patch)?\s*\n(.*?)\n```', flags=re.S | re.I)
    for match in fence_pattern.finditer(text):
        candidate = match.group(1).strip('\n')
        if looks_like_patch(candidate):
            return ensure_final_newline(candidate)
    lines = text.strip('\n').splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.startswith('diff --git ') or line.startswith('--- a/'):
            start = i
            break
    if start is None:
        return ''
    patch_lines = []
    for line in lines[start:]:
        if line.strip() == '```':
            break
        patch_lines.append(line)
    candidate = '\n'.join(patch_lines).strip('\n')
    return ensure_final_newline(candidate) if looks_like_patch(candidate) else ''


def patch_from_json_file(path: Path) -> str:
    try:
        data = json.loads(path.read_text(encoding='utf-8'))
    except Exception:
        return ''
    if isinstance(data, dict):
        for key in ['model_patch', 'patch', 'prediction', 'diff']:
            value = data.get(key)
            if isinstance(value, str) and looks_like_patch(value):
                return ensure_final_newline(value)
    return ''


def extract_patch_from_swe_agent_artifacts(run_dir: Path, command_output: str) -> str:
    for path in sorted(run_dir.rglob('*')):
        if not path.is_file() or path.stat().st_size > 5_000_000:
            continue
        if path.suffix.lower() in {'.json', '.pred'}:
            patch = patch_from_json_file(path)
            if patch:
                return patch
        if path.suffix.lower() in {'.patch', '.diff'}:
            text = path.read_text(encoding='utf-8', errors='replace')
            if looks_like_patch(text):
                return ensure_final_newline(text)
    return extract_unified_diff(command_output)


def reset_repo(package: Path) -> None:
    repo = package / 'repo'
    ensure_git_safe_directory(package, repo)
    run(['git', '-C', str(repo), 'reset', '--hard', 'HEAD'], package)
    run(['git', '-C', str(repo), 'clean', '-fdx'], package)


def ensure_git_safe_directory(package: Path, repo: Path) -> None:
    if repo.is_dir():
        run(['git', 'config', '--global', '--add', 'safe.directory', str(repo)], package)


def prune_repo_build_artifacts(package: Path) -> list[str]:
    repo = package / 'repo'
    removed: list[str] = []
    if not repo.is_dir():
        return removed
    ensure_git_safe_directory(package, repo)
    for path in sorted(repo.rglob('*'), key=lambda p: len(p.parts), reverse=True):
        if not path.is_dir() or path.name not in REPO_BUILD_ARTIFACT_DIRS:
            continue
        if '.git' in path.relative_to(repo).parts:
            continue
        code, out = run(['git', '-C', str(repo), 'ls-files', '--', str(path.relative_to(repo))], package)
        if code == 0 and out.strip():
            continue
        shutil.rmtree(path, ignore_errors=True)
        removed.append(path.relative_to(repo).as_posix())
    if removed:
        log('removed repo build artifacts before SWE-agent upload: ' + ', '.join(removed[:12]) + (f' ... +{len(removed) - 12}' if len(removed) > 12 else ''))
    return removed


def apply_patch(package: Path, patch: Path) -> tuple[int, str]:
    return run(['git', '-C', str(package / 'repo'), 'apply', str(patch)], package)


def prepare_untracked_files_for_diff(package: Path) -> None:
    run(['git', '-C', str(package / 'repo'), 'add', '-N', '.'], package)


def first_cmd(task: dict, key: str) -> str:
    value = task.get(key) or []
    if isinstance(value, list) and value:
        return str(value[0])
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
            if isinstance(parsed, list) and parsed:
                return str(parsed[0])
        except json.JSONDecodeError:
            pass
    return ''


def is_infrastructure_failure(output: str) -> bool:
    lower = (output or '').lower()
    explicit_before_failure = (
        'before_repo_set_cmd infrastructure failure',
        'before_repo_set_cmd eval-shell preflight failed',
    )
    return any(needle in lower for needle in explicit_before_failure) or any(
        needle in lower for needle in INFRASTRUCTURE_FAILURE_NEEDLES
    )


def is_compile_failure(output: str) -> bool:
    lower = (output or '').lower()
    needles = (
        'compile error',
        'compilation failed',
        'syntaxerror',
        'indentationerror',
        'taberror',
        'cannot find symbol',
        'undefined:',
        'build failed',
        'failed to compile',
        'error: could not compile',
    )
    return any(needle in lower for needle in needles)


def is_model_failure(output: str) -> bool:
    lower = (output or '').lower()
    needles = (
        'maximum agent steps exceeded',
        'reached max_steps',
        'without producing a patch',
        'no patch found in swe-agent output',
        'partial_no_submit',
        'timed out after',
        'timeoutexpired',
    )
    return any(needle in lower for needle in needles) or ('api calls' in lower and 'exceeds limit' in lower)


def standard_status(result: dict, log_text: str = '') -> str:
    if result.get('passed'):
        return 'resolved'
    error = str(result.get('error') or '')
    signal = (error + '\n' + (log_text or '')).lower()
    if is_infrastructure_failure(error):
        return 'test_infra_failed'
    if is_model_failure(signal):
        return 'model_failed'
    if any(needle in signal for needle in (
        'model patch did not apply',
        'test patch did not apply',
        'swe-agent patch did not apply',
        'patch did not apply',
    )):
        return 'patch_apply_failed'
    if result.get('model_patch_applied') and (
        result.get('fail_to_pass_passed') or result.get('pass_to_pass_passed')
    ):
        return 'partial'
    if is_compile_failure(signal):
        return 'compile_error'
    return 'invalid'


def test_output_for_result(result: dict, status: str) -> dict:
    return {
        'status': status,
        'success': status == 'resolved',
        'model_patch_applied': bool(result.get('model_patch_applied')),
        'test_patch_applied': bool(result.get('test_patch_applied')),
        'fail_to_pass_passed': bool(result.get('fail_to_pass_passed')),
        'pass_to_pass_passed': bool(result.get('pass_to_pass_passed')),
        'error': result.get('error'),
        'files_changed': result.get('files_changed') or [],
    }


def safe_task_metadata(package: Path, task: dict | None = None) -> dict:
    task = task or read_task(package)
    metadata = task.get('metadata') if isinstance(task.get('metadata'), dict) else {}
    evidence_links = task.get('evidence_links') if isinstance(task.get('evidence_links'), list) else []
    return {
        'instance_id': task.get('instance_id') or task.get('task_id') or package.name,
        'repo': task.get('repo'),
        'source_pr': metadata.get('source_pr') or task.get('source_pr'),
        'issue_url': next((link for link in evidence_links if isinstance(link, str) and '/issues/' in link), ''),
        'base_commit': task.get('base_commit'),
        'model_input_oracle_free': True,
    }


def write_evaluation_artifacts(
    package: Path,
    run_dir: Path,
    result: dict,
    model: str,
    task: dict | None = None,
) -> dict:
    eval_log = run_dir / 'eval.log'
    log_text = eval_log.read_text(encoding='utf-8', errors='replace') if eval_log.is_file() else ''
    status = standard_status(result, log_text)
    result['status'] = status
    test_output = test_output_for_result(result, status)
    (run_dir / 'test_output.json').write_text(json.dumps(test_output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    metadata = safe_task_metadata(package, task)
    report = [
        '# PR-Based Model Evaluation Report',
        '',
        f"- instance_id: {metadata['instance_id']}",
        f"- repo: {metadata.get('repo') or ''}",
        f"- source_pr: {metadata.get('source_pr') or ''}",
        f"- issue_url: {metadata.get('issue_url') or ''}",
        f"- base_commit: {metadata.get('base_commit') or ''}",
        f"- model_or_agent: {model}",
        '- pr_diff_hidden_from_model: true',
        '- model_input_contains_gold_patch: false',
        f"- conclusion: {status}",
        '',
        '## Patch Application',
        '',
        f"- model_patch_applied: {test_output['model_patch_applied']}",
        f"- test_patch_applied: {test_output['test_patch_applied']}",
        '',
        '## Tests',
        '',
        f"- fail_to_pass_passed: {test_output['fail_to_pass_passed']}",
        f"- pass_to_pass_passed: {test_output['pass_to_pass_passed']}",
        '',
        '## Files Changed',
        '',
        *(f"- {path}" for path in test_output['files_changed']),
    ]
    if result.get('error'):
        report.extend(['', '## Error', '', str(result['error'])])
    (run_dir / 'evaluation_report.md').write_text('\n'.join(report).rstrip() + '\n', encoding='utf-8')
    return test_output


def materialize_patch_from_swe_agent(package: Path, run_dir: Path, patch_text: str) -> list[str]:
    if not patch_text.strip():
        raise RuntimeError('no patch found in SWE-agent output')
    candidate = run_dir / 'candidate.patch'
    if not patch_text.endswith('\n'):
        patch_text += '\n'
    candidate.write_text(patch_text, encoding='utf-8')
    reset_repo(package)
    code, output = apply_patch(package, candidate)
    if code:
        raise RuntimeError('SWE-agent patch did not apply while materializing: ' + output.strip())
    prepare_untracked_files_for_diff(package)
    _, diff = run(['git', '-C', str(package / 'repo'), 'diff', '--binary'], package)
    if not diff.strip():
        raise RuntimeError('model patch is empty')
    model_patch = run_dir / 'model.patch'
    model_patch.write_text(diff, encoding='utf-8')
    _, changed = run(['git', '-C', str(package / 'repo'), 'diff', '--name-only'], package)
    reset_repo(package)
    return [line for line in changed.splitlines() if line.strip()]


def materialize_current_repo_diff(package: Path, run_dir: Path) -> list[str]:
    prepare_untracked_files_for_diff(package)
    _, diff = run(['git', '-C', str(package / 'repo'), 'diff', '--binary'], package)
    if not diff.strip():
        return []
    (run_dir / 'model.patch').write_text(diff, encoding='utf-8')
    _, changed = run(['git', '-C', str(package / 'repo'), 'diff', '--name-only'], package)
    return [line for line in changed.splitlines() if line.strip()]


def docker_eval_script(task: dict, phase: str) -> str:
    before_cmd = str(task.get('before_repo_set_cmd') or '').strip()
    test_key = 'fail_to_pass' if phase == 'fail_to_pass' else 'pass_to_pass'
    test_cmd = first_cmd(task, test_key)
    lines = [
        '#!/usr/bin/env bash',
        'set +e',
        'cd /workspace/repo || exit 97',
        'run_step() {',
        '  name="$1"',
        '  shift',
        '  echo "__SWE_EVAL_STEP__ ${name}"',
        '  "$@"',
        '  rc=$?',
        '  echo "__SWE_EVAL_RC__ ${name} ${rc}"',
        '  return "$rc"',
        '}',
        'run_shell_step() {',
        '  name="$1"',
        '  command="$2"',
        '  echo "__SWE_EVAL_STEP__ ${name}"',
        '  bash -lc "$command"',
        '  rc=$?',
        '  echo "__SWE_EVAL_RC__ ${name} ${rc}"',
        '  return "$rc"',
        '}',
        'run_step model_patch git apply /workspace/model_eval/model.patch',
        'model_rc=$?',
        'if [ "$model_rc" -ne 0 ]; then exit 0; fi',
    ]
    if phase == 'fail_to_pass':
        lines.extend([
            'run_step test_patch git apply /workspace/patches/test.patch',
            'test_rc=$?',
            'if [ "$test_rc" -ne 0 ]; then exit 0; fi',
        ])
    if before_cmd:
        lines.append('run_shell_step before_repo_set_cmd ' + shlex.quote(before_cmd))
        lines.append('before_rc=$?')
        lines.append('if [ "$before_rc" -ne 0 ]; then exit 0; fi')
    if test_cmd:
        lines.append('run_shell_step ' + test_key + ' ' + shlex.quote(test_cmd))
    lines.append('exit 0')
    return '\n'.join(lines) + '\n'


def docker_eval_rcs(output: str) -> dict[str, int]:
    rcs: dict[str, int] = {}
    for match in re.finditer(r'__SWE_EVAL_RC__\s+([A-Za-z0-9_-]+)\s+(\d+)', output):
        rcs[match.group(1)] = int(match.group(2))
    return rcs


def run_docker_eval_phase(package: Path, run_dir: Path, image: str, task: dict, phase: str) -> tuple[dict[str, int], str]:
    script = run_dir / f'evaluate_{phase}.sh'
    script.write_text(docker_eval_script(task, phase), encoding='utf-8')
    cmd = [
        'docker', 'run', '--rm',
        *docker_run_proxy_args(),
        '-v', f'{run_dir}:/workspace/model_eval:ro',
        image,
        'bash',
        f'/workspace/model_eval/{script.name}',
    ]
    code, output = run(cmd, package, env=docker_proxy_env())
    if code != 0:
        output = output + f'\nERROR: docker evaluation phase {phase} exited {code}\n'
    return docker_eval_rcs(output), output


def preflight_eval_shell(package: Path, out: Path, image: str, task: dict) -> dict:
    before_cmd = str(task.get('before_repo_set_cmd') or '').strip()
    run_dir = out / 'eval_shell_preflight'
    run_dir.mkdir(parents=True, exist_ok=True)
    log_path = run_dir / 'eval_shell_preflight.log'
    result = {
        'phase': 'eval_shell_preflight',
        'passed': True,
        'image': image,
        'before_repo_set_cmd_present': bool(before_cmd),
        'log_path': artifact_path(package, log_path),
    }
    if not before_cmd:
        log_path.write_text('before_repo_set_cmd empty; skipped\n', encoding='utf-8')
        result['skipped'] = True
        return result
    cmd = [
        'docker', 'run', '--rm',
        *docker_run_proxy_args(),
        image,
        'bash',
        '-lc',
        'cd /workspace/repo && ' + before_cmd,
    ]
    code, output = run(cmd, package, env=docker_proxy_env())
    log_path.write_text(output, encoding='utf-8')
    if code != 0:
        result['passed'] = False
        result['exit_code'] = code
        raise RuntimeError('before_repo_set_cmd eval-shell preflight failed')
    result['exit_code'] = 0
    return result


def evaluate_model_patch(
    package: Path,
    run_dir: Path,
    files_changed: list[str],
    validation_image: str,
    task: dict | None = None,
) -> dict:
    task = task or read_task(package)
    result = {
        'model_patch_applied': False,
        'test_patch_applied': False,
        'fail_to_pass_passed': False,
        'pass_to_pass_passed': False,
        'passed': False,
        'error': None,
        'files_changed': files_changed,
        'validation_image': validation_image,
    }
    lines: list[str] = []
    try:
        lines.append(f'$ docker validation image: {validation_image}')
        fail_rcs, fail_output = run_docker_eval_phase(package, run_dir, validation_image, task, 'fail_to_pass')
        lines.append(fail_output)
        result['model_patch_applied'] = fail_rcs.get('model_patch') == 0
        result['test_patch_applied'] = fail_rcs.get('test_patch') == 0
        if fail_rcs.get('before_repo_set_cmd', 0) != 0:
            raise RuntimeError('before_repo_set_cmd infrastructure failure')
        result['fail_to_pass_passed'] = fail_rcs.get('fail_to_pass') == 0

        pass_rcs, pass_output = run_docker_eval_phase(package, run_dir, validation_image, task, 'pass_to_pass')
        lines.append(pass_output)
        if pass_rcs.get('before_repo_set_cmd', 0) != 0:
            raise RuntimeError('before_repo_set_cmd infrastructure failure')
        result['pass_to_pass_passed'] = pass_rcs.get('pass_to_pass') == 0
        result['passed'] = all([
            result['model_patch_applied'],
            result['test_patch_applied'],
            result['fail_to_pass_passed'],
            result['pass_to_pass_passed'],
        ])
    except Exception as exc:
        result['error'] = str(exc)
        lines.append('ERROR: ' + str(exc))
    finally:
        (run_dir / 'eval.log').write_text('\n'.join(lines) + ('\n' if lines else ''), encoding='utf-8')
        reset_repo(package)
    return result


def write_summary(package: Path, out: Path, out_name: str, model: str, base_url: str, results: list[dict]) -> dict:
    model_results = [result for result in results if result.get('phase') != 'image_preflight']
    attempts = len(model_results)
    passes = sum(1 for result in model_results if result.get('passed'))
    pass_rate = passes / attempts if attempts else 0
    status_counts = {status: 0 for status in sorted(STANDARD_EVALUATION_STATUSES)}
    for result in results:
        status = result.get('status') or standard_status(result)
        if status not in status_counts:
            status_counts[status] = 0
        status_counts[status] += 1
    summary = {
        'model': model,
        'base_url': REDACTED_BASE_URL if base_url else '',
        'requested_out_name': out_name,
        'output_dir': str(out.relative_to(package)),
        'attempts': attempts,
        'passes': passes,
        'pass_rate': pass_rate,
        'status_counts': status_counts,
        'standard_statuses': sorted(STANDARD_EVALUATION_STATUSES),
        'pass_nonzero': passes > 0,
        'pass_rate_lte_50_percent': pass_rate <= 0.5,
        'model_calls_started': sum(1 for result in model_results if result.get('model_api_raw_response_exists')),
        'preflight_passed': not any(result.get('phase') == 'image_preflight' and not result.get('passed') for result in results),
        'artifacts': {
            'problem_statement': artifact_path(package, package / 'model_evaluation' / '_swe_agent_problem_statement.md'),
            'guard_config': artifact_path(package, package / 'model_evaluation' / '_swe_agent_guard.yaml'),
        },
        'results': results,
    }
    out.mkdir(parents=True, exist_ok=True)
    (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return summary


def unique_output_dir(base: Path) -> Path:
    if not base.exists() or not any(base.iterdir()):
        return base
    stamp = time.strftime('%Y%m%d-%H%M%S')
    candidate = base.with_name(f'{base.name}_{stamp}')
    idx = 2
    while candidate.exists():
        candidate = base.with_name(f'{base.name}_{stamp}_{idx}')
        idx += 1
    return candidate


def resolve_sweagent_bin(root: Path) -> str:
    candidates = [
        root / '.venv' / 'bin' / 'sweagent',
        root / 'venv' / 'bin' / 'sweagent',
        root / 'sweagent',
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    found = shutil.which('sweagent')
    if found:
        return found
    raise RuntimeError(f'sweagent executable not found under {root} or PATH')


def model_name_for_swe_agent(model: str, provider: str) -> str:
    if '/' in model:
        return model
    if provider == 'anthropic':
        return 'anthropic/' + model
    if provider == 'openai':
        return 'openai/' + model
    return model


def swe_agent_env(api_key_env: str, api_key: str, enable_thinking: str) -> dict[str, str]:
    env = os.environ.copy()
    env[api_key_env] = api_key
    env['OPENAI_API_KEY'] = api_key
    env['DASHSCOPE_API_KEY'] = api_key
    if enable_thinking in {'true', 'false'}:
        env['ENABLE_THINKING'] = enable_thinking
    return env


def normalize_http_host(host: str) -> str:
    host = (host or '').strip()
    if host and not host.startswith(('http://', 'https://')):
        host = 'http://' + host
    return host


def default_swe_rex_runtime_host() -> str:
    configured = os.environ.get('SWE_REX_RUNTIME_HOST')
    if configured:
        return normalize_http_host(configured)
    if Path('/.dockerenv').exists():
        return 'http://host.docker.internal'
    return ''


def write_swe_rex_runtime_host_patch(package: Path) -> Path:
    patch_dir = package / 'model_evaluation' / '_swe_agent_runtime_patch'
    patch_dir.mkdir(parents=True, exist_ok=True)
    sitecustomize = patch_dir / 'sitecustomize.py'
    sitecustomize.write_text(
        "import os\n"
        "host = os.environ.get('SWE_REX_RUNTIME_HOST')\n"
        "if host:\n"
        "    try:\n"
        "        from swerex.runtime.config import RemoteRuntimeConfig\n"
        "        field = RemoteRuntimeConfig.model_fields.get('host')\n"
        "        if field is not None:\n"
        "            field.default = host\n"
        "            RemoteRuntimeConfig.model_rebuild(force=True)\n"
        "    except Exception as exc:\n"
        "        print(f'[swe-agent-runtime-patch] failed to set SWE-ReX runtime host: {exc}', flush=True)\n",
        encoding='utf-8',
    )
    return patch_dir


def apply_swe_rex_runtime_host_patch(env: dict[str, str], package: Path, host: str) -> None:
    host = normalize_http_host(host)
    if not host:
        return
    patch_dir = write_swe_rex_runtime_host_patch(package)
    env['SWE_REX_RUNTIME_HOST'] = host
    existing = env.get('PYTHONPATH')
    env['PYTHONPATH'] = str(patch_dir) if not existing else str(patch_dir) + os.pathsep + existing


def swe_agent_api_key_reference(api_key_env: str) -> str:
    if not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', api_key_env):
        raise ValueError(f'invalid API key env var name: {api_key_env}')
    return f'${api_key_env}'


def write_swe_agent_guard_config(package: Path, args: argparse.Namespace) -> Path:
    """Write runtime guardrails that are merged after SWE-agent's default config."""
    max_steps = max(1, int(args.agent_max_steps))
    max_input_tokens = max(1, int(args.max_input_tokens))
    keep_observations = max(1, int(args.history_observations))
    call_limit = max_steps + max(2, min(4, max_steps // 2))
    lines = [
        'agent:',
        '  model:',
        f'    max_input_tokens: {max_input_tokens}',
        f'    per_instance_call_limit: {call_limit}',
        '  tools:',
        '    parse_function:',
        '      type: single_bash_code_block',
        '  history_processors:',
        '    - type: last_n_observations',
        f'      n: {keep_observations}',
        '      polling: 1',
        '    - type: remove_regex',
        '      keep_last: 4',
        '      remove:',
        "        - '<diff>[\\s\\S]*?</diff>'",
        '    - type: cache_control',
        '      last_n_messages: 2',
    ]
    if args.enable_thinking in {'true', 'false'}:
        lines[3:3] = [
            '    completion_kwargs:',
            '      extra_body:',
            f'        enable_thinking: {args.enable_thinking}',
        ]
    out = package / 'model_evaluation' / '_swe_agent_guard.yaml'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return out


def parse_max_steps_schedule(schedule: str, fallback: int) -> list[int]:
    values: list[int] = []
    for raw in (schedule or '').split(','):
        raw = raw.strip()
        if not raw:
            continue
        try:
            value = int(raw)
        except ValueError:
            continue
        if value > 0:
            values.append(value)
    return values or [max(1, int(fallback))]


def max_steps_for_attempt(schedule: list[int], attempt: int) -> int:
    if attempt <= len(schedule):
        return schedule[attempt - 1]
    return schedule[-1]


def run_swe_agent_attempt(
        package: Path,
        run_dir: Path,
        args: argparse.Namespace,
        problem_path: Path,
        safe_image: str | None,
        validation_image: str,
        task: dict | None = None) -> dict:
    task = task or read_task(package)
    sweagent_bin = resolve_sweagent_bin(args.swe_agent_root)
    output_dir = run_dir / 'swe_agent'
    output_dir.mkdir(parents=True, exist_ok=True)
    reset_repo(package)
    prune_repo_build_artifacts(package)
    guard_config = write_swe_agent_guard_config(package, args)
    cmd = [
        sweagent_bin,
        'run',
        '--config',
        'config/bash_only.yaml',
        '--config',
        str(guard_config),
        f'--agent.model.name={model_name_for_swe_agent(args.model, args.provider)}',
        f'--agent.model.api_base={args.base_url}',
        f'--agent.model.api_key={swe_agent_api_key_reference(args.api_key_env)}',
        f'--agent.model.temperature={args.temperature}',
        f'--agent.model.max_output_tokens={args.max_tokens}',
        '--agent.model.per_instance_cost_limit=0',
        '--agent.model.total_cost_limit=0',
        f'--agent.model.max_input_tokens={args.max_input_tokens}',
        f'--env.repo.path={package / "repo"}',
        f'--env.repo.base_commit={task.get("base_commit") or "HEAD"}',
        f'--problem_statement.path={problem_path}',
        '--actions.apply_patch_locally=True',
        f'--output_dir={output_dir}',
    ]
    if safe_image:
        cmd.append(f'--env.deployment.image={safe_image}')
    env = swe_agent_env(args.api_key_env, args.api_key, args.enable_thinking)
    apply_swe_rex_runtime_host_patch(env, package, args.swe_rex_runtime_host)
    code, output = run(cmd, args.swe_agent_root, env=env, timeout=args.timeout)
    (run_dir / 'swe_agent_output.raw.log').write_text(redact_secret_log_text(output), encoding='utf-8')
    (run_dir / 'swe_agent_output.log').write_text(redact_display_log_text(output), encoding='utf-8')
    if code:
        raise RuntimeError(f'SWE-agent exited {code}')
    patch = extract_patch_from_swe_agent_artifacts(run_dir, output)
    if not patch.strip() and 'Maximum agent steps exceeded' in output:
        files_changed = materialize_current_repo_diff(package, run_dir)
        if not files_changed:
            raise RuntimeError(f'SWE-agent reached max_steps={args.agent_max_steps} without producing a patch')
        result = evaluate_model_patch(package, run_dir, files_changed, validation_image, task)
        result['partial_no_submit'] = True
        if result.get('error'):
            result['error'] = f"partial_no_submit: {result['error']}"
        write_evaluation_artifacts(package, run_dir, result, args.model, task)
        return result
    files_changed = materialize_patch_from_swe_agent(package, run_dir, patch)
    result = evaluate_model_patch(package, run_dir, files_changed, validation_image, task)
    write_evaluation_artifacts(package, run_dir, result, args.model, task)
    return result


def model_api_artifact_info(package: Path, run_dir: Path) -> dict:
    raw_response = run_dir / 'model_api_raw_responses.jsonl'
    swe_agent_output = run_dir / 'swe_agent_output.log'
    raw_text = raw_response.read_text(encoding='utf-8', errors='replace') if raw_response.is_file() else ''
    return {
        'model_api_raw_response_path': artifact_path(package, raw_response),
        'model_api_raw_response_exists': raw_response.is_file(),
        'model_api_raw_response_bytes': raw_response.stat().st_size if raw_response.is_file() else 0,
        'model_api_raw_response_lines': len([line for line in raw_text.splitlines() if line.strip()]),
        'swe_agent_output_log': artifact_path(package, swe_agent_output),
        'swe_agent_output_log_exists': swe_agent_output.is_file(),
    }


def write_infra_failure_summary(
    package: Path,
    out: Path,
    out_name: str,
    model: str,
    base_url: str,
    error: str,
    preflight_summary: dict | None = None,
) -> dict:
    run_dir = out / 'image_preflight'
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / 'eval.log').write_text('ERROR: ' + error + '\n', encoding='utf-8')
    result = {
        'phase': 'image_preflight',
        'attempt': 0,
        'passed': False,
        'status': 'test_infra_failed',
        'model_call_started': False,
        'error': error,
    }
    if preflight_summary:
        result['preflight'] = preflight_summary
    return write_summary(package, out, out_name, model, base_url, [result])


def main() -> int:
    parser = argparse.ArgumentParser(description='Run SWE-agent model evaluations for a SWE-Pro task package.')
    parser.add_argument('package_dir')
    parser.add_argument('--swe-agent-root', type=Path, required=True)
    parser.add_argument('--swe-rex-runtime-host', default=default_swe_rex_runtime_host())
    parser.add_argument('--model', required=True)
    parser.add_argument('--base-url', required=True)
    parser.add_argument('--api-key-env', required=True)
    parser.add_argument('--attempts', type=int, required=True)
    parser.add_argument('--out-name', required=True)
    parser.add_argument('--timeout', type=int, default=3600)
    parser.add_argument('--max-tokens', type=int, default=4096)
    parser.add_argument('--max-input-tokens', type=int, default=22000)
    parser.add_argument('--temperature', type=float, default=0.7)
    parser.add_argument('--provider', choices=['openai', 'anthropic'], default='openai')
    parser.add_argument('--agent-max-steps', type=int, default=20)
    parser.add_argument('--agent-max-steps-schedule', default='', help='comma-separated max_steps per attempt; last value repeats')
    parser.add_argument('--history-observations', type=int, default=12)
    parser.add_argument('--enable-thinking', choices=['true', 'false', 'omit'], default='omit')
    parser.add_argument('--preflight-only', action='store_true')
    args = parser.parse_args()

    package = Path(args.package_dir).resolve()
    args.swe_agent_root = args.swe_agent_root.resolve()
    args.api_key = os.environ.get(args.api_key_env)
    if not args.api_key:
        raise SystemExit(f'missing API key env: {args.api_key_env}')
    if not args.swe_agent_root.is_dir():
        raise SystemExit(f'SWE-agent root does not exist: {args.swe_agent_root}')

    out = unique_output_dir(package / 'model_evaluation' / args.out_name)
    out.mkdir(parents=True, exist_ok=True)
    task_snapshot = read_task(package)
    safe_image = None
    validation_image = None
    preflight_summary: dict | None = None
    validation_image_preflight_summary: dict | None = None
    eval_shell_preflight_summary: dict | None = None
    try:
        safe_image = ensure_swe_agent_docker_image(package, task_snapshot)
        validation_image = ensure_validation_docker_image(package)
        validation_image_preflight_summary = verify_validation_image_task_specs(package, out, validation_image)
        preflight_summary = preflight_model_safe_image(package, safe_image)
        eval_shell_preflight_summary = preflight_eval_shell(package, out, validation_image, task_snapshot)
    except Exception as exc:
        write_infra_failure_summary(
            package,
            out,
            args.out_name,
            args.model,
            args.base_url,
            str(exc),
            {
                'validation_image_preflight': validation_image_preflight_summary,
                'image_preflight': preflight_summary,
                'eval_shell_preflight': eval_shell_preflight_summary,
            },
        )
        raise
    max_steps_schedule = parse_max_steps_schedule(args.agent_max_steps_schedule, args.agent_max_steps)
    first_max_steps = max_steps_for_attempt(max_steps_schedule, 1)
    args.agent_max_steps = first_max_steps
    write_swe_agent_problem_statement(package, first_max_steps, task_snapshot)
    write_swe_agent_guard_config(package, args)
    problem_path = package / 'model_evaluation' / '_swe_agent_problem_statement.md'
    if args.preflight_only:
        summary = write_summary(package, out, args.out_name, args.model, args.base_url, [])
        summary['preflight'] = preflight_summary
        (out / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'preflight_passed': True, 'safe_image': safe_image}, ensure_ascii=False))
        return 0
    results: list[dict] = []
    for attempt in range(1, args.attempts + 1):
        attempt_max_steps = max_steps_for_attempt(max_steps_schedule, attempt)
        args.agent_max_steps = attempt_max_steps
        write_swe_agent_problem_statement(package, attempt_max_steps, task_snapshot)
        run_dir = out / f'run_{attempt:02d}'
        run_dir.mkdir(parents=True, exist_ok=True)
        result = {
            'attempt': attempt,
            'agent_max_steps': attempt_max_steps,
            'safe_image': safe_image,
            'validation_image': validation_image,
            'preflight': preflight_summary,
        }
        try:
            result.update(run_swe_agent_attempt(
                package,
                run_dir,
                args,
                problem_path,
                safe_image,
                validation_image,
                task_snapshot,
            ))
        except Exception as exc:
            result.update({'passed': False, 'error': str(exc)})
            output_log = run_dir / 'swe_agent_output.log'
            output_tail = ''
            if output_log.is_file():
                text = output_log.read_text(encoding='utf-8', errors='replace')
                output_tail = '\n\nSWE-agent output tail:\n' + text[-8000:]
            (run_dir / 'eval.log').write_text('ERROR: ' + str(exc) + output_tail + '\n', encoding='utf-8')
            reset_repo(package)
            write_evaluation_artifacts(package, run_dir, result, args.model, task_snapshot)
        result.update(model_api_artifact_info(package, run_dir))
        results.append(result)
        write_summary(package, out, args.out_name, args.model, args.base_url, results)
        print(f"run_{attempt:02d}: {'PASS' if result.get('passed') else 'FAIL'}" + (f" ({result.get('error')})" if result.get('error') else ''), flush=True)
    summary = write_summary(package, out, args.out_name, args.model, args.base_url, results)
    print(json.dumps({'passes': summary['passes'], 'attempts': summary['attempts'], 'pass_rate': summary['pass_rate']}, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
