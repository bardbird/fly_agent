#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shlex
import tomllib
from pathlib import Path


NODE_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash build-essential && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y --no-install-recommends nodejs && rm -rf /var/lib/apt/lists/* && node --version && npm --version && git --version'
PYTHON_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash python3 python3-pip python3-venv python3-dev build-essential && rm -rf /var/lib/apt/lists/* && python3 --version && python3 -m pip --version'
JAVA_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash openjdk-17-jdk-headless maven gradle && rm -rf /var/lib/apt/lists/* && java -version && mvn -version && gradle --version'
GO_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash build-essential unzip && ARCH="$(dpkg --print-architecture)" && case "$ARCH" in amd64) GOARCH=amd64 ;; arm64) GOARCH=arm64 ;; *) echo "unsupported Go architecture: $ARCH" >&2; exit 1 ;; esac && curl -fsSL "https://go.dev/dl/go1.25.1.linux-${GOARCH}.tar.gz" -o /tmp/go.tgz && tar -C /usr/local -xzf /tmp/go.tgz && ln -s /usr/local/go/bin/go /usr/local/bin/go && ln -s /usr/local/go/bin/gofmt /usr/local/bin/gofmt && rm -f /tmp/go.tgz && rm -rf /var/lib/apt/lists/* && go env -w GOPROXY=https://goproxy.cn,direct GOSUMDB=sum.golang.google.cn && go version'
RUST_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash build-essential pkg-config libssl-dev libxtst-dev libx11-dev libwayland-dev libxkbcommon-dev && curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal && ln -s /root/.cargo/bin/cargo /usr/local/bin/cargo && ln -s /root/.cargo/bin/rustc /usr/local/bin/rustc && rm -rf /var/lib/apt/lists/* && rustc --version && cargo --version'
C_CPP_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash build-essential cmake pkg-config && rm -rf /var/lib/apt/lists/* && gcc --version && g++ --version && cmake --version'
PHP_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash php-cli php-mbstring php-xml php-curl unzip && curl -fsSL https://getcomposer.org/installer -o /tmp/composer-setup.php && php /tmp/composer-setup.php --install-dir=/usr/local/bin --filename=composer && rm -f /tmp/composer-setup.php && rm -rf /var/lib/apt/lists/* && php --version && composer --version'
SWIFT_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash clang libicu-dev libcurl4-openssl-dev libxml2-dev libz3-dev zlib1g-dev && curl -fsSL https://download.swift.org/swift-6.0.3-release/ubuntu2204/swift-6.0.3-RELEASE/swift-6.0.3-RELEASE-ubuntu22.04.tar.gz -o /tmp/swift.tgz && tar -C /usr/local --strip-components=1 -xzf /tmp/swift.tgz && rm -f /tmp/swift.tgz && rm -rf /var/lib/apt/lists/* && swift --version'
KOTLIN_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash openjdk-17-jdk-headless gradle kotlin && rm -rf /var/lib/apt/lists/* && java -version && gradle --version && kotlinc -version'

TOOLCHAIN_BY_LANGUAGE = {
    'javascript': NODE_DEPENDENCIES_CMD,
    'typescript': NODE_DEPENDENCIES_CMD,
    'node': NODE_DEPENDENCIES_CMD,
    'python': PYTHON_DEPENDENCIES_CMD,
    'java': JAVA_DEPENDENCIES_CMD,
    'go': GO_DEPENDENCIES_CMD,
    'rust': RUST_DEPENDENCIES_CMD,
    'c_cpp': C_CPP_DEPENDENCIES_CMD,
    'php': PHP_DEPENDENCIES_CMD,
    'swift': SWIFT_DEPENDENCIES_CMD,
    'kotlin': KOTLIN_DEPENDENCIES_CMD,
}


def load_json(path: Path) -> dict:
    if not path.is_file():
        return {}
    return json.loads(path.read_text(encoding='utf-8'))


def read_text(path: Path) -> str:
    return path.read_text(encoding='utf-8', errors='ignore') if path.is_file() else ''


def safe_repo_relative(value: str) -> str | None:
    candidate = (value or '').strip().strip('"\'')
    for prefix in ('/workspace/repo/', 'repo/'):
        if candidate.startswith(prefix):
            candidate = candidate[len(prefix):]
    if candidate in ('/workspace/repo', 'repo', '.'):
        return ''
    candidate = candidate.removeprefix('./').strip('/')
    if not candidate or candidate.startswith('/') or '..' in Path(candidate).parts:
        return None
    if re.search(r'[$`;&|<>*?()[\]{}]', candidate):
        return None
    return candidate


def add_unique(values: list[str], value: str | None) -> None:
    if value and value not in values:
        values.append(value)


def task_commands(task: dict) -> list[str]:
    commands: list[str] = []
    value = task.get('before_repo_set_cmd')
    if isinstance(value, str) and value.strip():
        commands.append(value)
    for key in ('fail_to_pass', 'pass_to_pass'):
        raw = task.get(key)
        if isinstance(raw, list):
            commands.extend(str(item) for item in raw if str(item).strip())
    return commands


def command_blob(root: Path, task: dict) -> str:
    return '\n'.join([
        *task_commands(task),
        read_text(root / 'scripts' / 'run_selected_tests.sh'),
    ]).lower()


def repo_files(root: Path, names: tuple[str, ...], max_depth: int = 5) -> list[Path]:
    repo = root / 'repo'
    if not repo.is_dir():
        return []
    found: list[Path] = []
    for path in repo.rglob('*'):
        try:
            rel = path.relative_to(repo)
        except ValueError:
            continue
        if len(rel.parts) > max_depth or {'node_modules', 'vendor', '.git'} & set(rel.parts):
            continue
        if path.is_file() and path.name in names:
            found.append(path)
    return sorted(found)


def nearest_manifest_dir(root: Path, rel_file: str, manifest: str) -> str | None:
    safe = safe_repo_relative(rel_file)
    if safe is None:
        return None
    repo = root / 'repo'
    current = (repo / safe).parent
    while current != repo.parent:
        if (current / manifest).is_file():
            return '' if current == repo else current.relative_to(repo).as_posix()
        if current == repo:
            break
        current = current.parent
    return None


def selected_files(task: dict) -> list[str]:
    raw = task.get('selected_test_files_to_run')
    if not isinstance(raw, list):
        return []
    return [str(item) for item in raw if isinstance(item, str) and item.strip()]


def infer_npm_dirs(root: Path, task: dict, commands: list[str]) -> list[str]:
    repo = root / 'repo'
    dirs: list[str] = []
    text = '\n'.join(commands)
    for match in re.finditer(r'(?:^|[(&;])\s*cd\s+([^\s;&|()]+)\s+&&\s+(?:npm|pnpm|yarn|bun)\b', text, flags=re.M):
        rel = safe_repo_relative(match.group(1))
        if rel is not None and (repo / rel / 'package.json').is_file():
            add_unique(dirs, rel)
    for item in selected_files(task):
        add_unique(dirs, nearest_manifest_dir(root, item, 'package.json'))
    if (repo / 'package.json').is_file():
        add_unique(dirs, '')
    package_jsons = repo_files(root, ('package.json',), 6)
    if len(package_jsons) == 1:
        rel = package_jsons[0].parent.relative_to(repo).as_posix()
        add_unique(dirs, '' if rel == '.' else rel)
    for common in ('web', 'frontend', 'client', 'app'):
        if (repo / common / 'package.json').is_file():
            add_unique(dirs, common)
    return dirs


def npm_install_command(root: Path, rel: str) -> str:
    package_dir = root / 'repo' / rel
    if (package_dir / 'package-lock.json').is_file():
        command = 'npm ci --legacy-peer-deps'
    elif (package_dir / 'pnpm-lock.yaml').is_file():
        command = 'corepack enable && pnpm install --frozen-lockfile'
    elif (package_dir / 'yarn.lock').is_file():
        command = 'corepack enable && yarn install --frozen-lockfile'
    else:
        command = 'npm install --legacy-peer-deps'
    return command if rel == '' else f'cd {shlex.quote(rel)} && {command}'


def go_module_dirs(root: Path, task: dict) -> list[str]:
    repo = root / 'repo'
    dirs: list[str] = []
    for item in selected_files(task):
        add_unique(dirs, nearest_manifest_dir(root, item, 'go.mod'))
    for mod in repo_files(root, ('go.mod',), 5):
        rel = mod.parent.relative_to(repo).as_posix()
        add_unique(dirs, '' if rel == '.' else rel)
    return dirs


def pip_install_command(args: str) -> str:
    return f'(python3 -m pip install --break-system-packages {args} || python3 -m pip install {args})'


def pyproject_dev_dependencies(pyproject: Path) -> list[str]:
    try:
        data = tomllib.loads(pyproject.read_text(encoding='utf-8'))
    except Exception:
        return []
    groups = data.get('dependency-groups') if isinstance(data, dict) else None
    dev = groups.get('dev') if isinstance(groups, dict) else None
    if not isinstance(dev, list):
        return []
    deps: list[str] = []
    for item in dev:
        if isinstance(item, str) and item.strip() and not item.strip().startswith('-'):
            deps.append(item.strip())
    return deps


def python_manifest_dirs(root: Path, task: dict) -> list[str]:
    repo = root / 'repo'
    dirs: list[str] = []
    for item in selected_files(task):
        for manifest in ('pyproject.toml', 'setup.py', 'requirements.txt'):
            add_unique(dirs, nearest_manifest_dir(root, item, manifest))
    for manifest in repo_files(root, ('pyproject.toml', 'setup.py', 'requirements.txt'), 5):
        rel = manifest.parent.relative_to(repo).as_posix()
        add_unique(dirs, '' if rel == '.' else rel)
    return dirs


def python_setup_commands(root: Path, task: dict) -> list[str]:
    repo = root / 'repo'
    commands: list[str] = []
    for rel in python_manifest_dirs(root, task):
        package_dir = repo / rel
        prefix = '' if rel == '' else f'cd {shlex.quote(rel)} && '
        if (package_dir / 'requirements.txt').is_file():
            commands.append(prefix + pip_install_command('-r requirements.txt'))
        if (package_dir / 'pyproject.toml').is_file() or (package_dir / 'setup.py').is_file():
            commands.append(prefix + pip_install_command('-e .'))
        dev_deps = pyproject_dev_dependencies(package_dir / 'pyproject.toml')
        if dev_deps:
            commands.append(prefix + pip_install_command(' '.join(shlex.quote(dep) for dep in dev_deps)))
    if not commands:
        commands.append(pip_install_command('-e .'))
    return commands


def infer_languages(root: Path, task: dict) -> list[str]:
    signal = command_blob(root, task)
    languages: list[str] = []
    explicit = str(task.get('repo_language') or '').lower()
    if explicit in {'javascript', 'typescript', 'python', 'java', 'go', 'rust', 'php', 'swift', 'kotlin'}:
        add_unique(languages, explicit)
    if re.search(r'\b(npm|node|yarn|pnpm|npx|bun)\b', signal) or repo_files(root, ('package.json',), 5):
        add_unique(languages, 'typescript' if any(path.endswith(('.ts', '.tsx')) for path in selected_files(task)) else 'javascript')
    if re.search(r'\b(go test|go build|go mod|go run)\b', signal) or repo_files(root, ('go.mod',), 5):
        add_unique(languages, 'go')
    if re.search(r'\b(pytest|python|python3|pip|pip3)\b', signal) or repo_files(root, ('pyproject.toml', 'requirements.txt', 'setup.py'), 5):
        add_unique(languages, 'python')
    if re.search(r'\b(mvn|gradle|gradlew|java|javac)\b', signal) or repo_files(root, ('pom.xml', 'build.gradle', 'build.gradle.kts'), 5):
        add_unique(languages, 'java')
    if re.search(r'\b(cargo|rustc)\b', signal) or repo_files(root, ('Cargo.toml',), 5):
        add_unique(languages, 'rust')
    if re.search(r'\b(gcc|g\+\+|clang|clang\+\+|cc|c\+\+|cmake|ctest)\b', signal) or repo_files(root, ('CMakeLists.txt', 'Makefile'), 4):
        add_unique(languages, 'c_cpp')
    if re.search(r'\b(php|composer|phpunit)\b', signal) or repo_files(root, ('composer.json',), 5):
        add_unique(languages, 'php')
    if re.search(r'\bswift\s+(test|build)\b', signal) or repo_files(root, ('Package.swift',), 5):
        add_unique(languages, 'swift')
    if re.search(r'\b(kotlinc|kotlin)\b', signal):
        add_unique(languages, 'kotlin')
    return languages


def resolve_runtime_env(root: Path) -> dict:
    task = load_json(root / 'task.json')
    commands = task_commands(task)
    languages = infer_languages(root, task)
    setup_commands: list[str] = []
    package_managers: list[dict] = []

    if any(lang in languages for lang in ('javascript', 'typescript')):
        for rel in infer_npm_dirs(root, task, commands):
            command = npm_install_command(root, rel)
            package_managers.append({'type': 'node', 'workdir': rel or '.', 'install': command})
            add_unique(setup_commands, command)

    if 'go' in languages:
        for rel in go_module_dirs(root, task):
            command = 'go mod download' if rel == '' else f'cd {shlex.quote(rel)} && go mod download'
            package_managers.append({'type': 'go', 'workdir': rel or '.', 'install': command})
            add_unique(setup_commands, command)

    if 'python' in languages:
        for command in python_setup_commands(root, task):
            package_managers.append({'type': 'python', 'workdir': '.', 'install': command})
            add_unique(setup_commands, command)

    if 'java' in languages:
        if (root / 'repo' / 'pom.xml').is_file():
            add_unique(setup_commands, 'mvn -q -DskipTests dependency:resolve')
        elif (root / 'repo' / 'gradlew').is_file():
            add_unique(setup_commands, './gradlew dependencies --no-daemon || true')
        elif (root / 'repo' / 'build.gradle').is_file() or (root / 'repo' / 'build.gradle.kts').is_file():
            add_unique(setup_commands, 'gradle dependencies --no-daemon || true')

    if 'rust' in languages:
        add_unique(setup_commands, 'cargo fetch')

    existing_before = str(task.get('before_repo_set_cmd') or '').strip()
    if existing_before and existing_before not in setup_commands:
        setup_commands.insert(0, existing_before)

    dependency_commands: list[str] = []
    for lang in languages:
        add_unique(dependency_commands, TOOLCHAIN_BY_LANGUAGE.get(lang))

    return {
        'schema_version': 1,
        'source': 'deterministic_runtime_env_resolver',
        'base_commit': task.get('base_commit') or '',
        'repo': task.get('repo') or '',
        'languages': languages,
        'package_managers': package_managers,
        'setup_commands': setup_commands,
        'test_commands': {
            'fail_to_pass': task.get('fail_to_pass') if isinstance(task.get('fail_to_pass'), list) else [],
            'pass_to_pass': task.get('pass_to_pass') if isinstance(task.get('pass_to_pass'), list) else [],
        },
        'docker': {
            'base_image': 'ubuntu:22.04',
            'dependency_commands': dependency_commands,
        },
    }


def update_task_metadata(root: Path, runtime_env: dict) -> None:
    task_path = root / 'task.json'
    task = load_json(task_path)
    if not task:
        return
    metadata = task.setdefault('metadata', {})
    metadata['runtime_env'] = {
        'schema_version': runtime_env['schema_version'],
        'path': 'runtime_env.json',
        'languages': runtime_env['languages'],
        'setup_command_count': len(runtime_env['setup_commands']),
        'docker_dependency_count': len(runtime_env['docker']['dependency_commands']),
    }
    task_path.write_text(json.dumps(task, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main() -> int:
    parser = argparse.ArgumentParser(description='Resolve deterministic runtime dependencies for a SWE-Pro task package.')
    parser.add_argument('package_dir')
    parser.add_argument('--check', action='store_true', help='Fail if runtime_env.json is missing or stale.')
    parser.add_argument('--update-task-metadata', action='store_true')
    args = parser.parse_args()

    root = Path(args.package_dir).resolve()
    runtime_env = resolve_runtime_env(root)
    out = root / 'runtime_env.json'
    text = json.dumps(runtime_env, ensure_ascii=False, indent=2) + '\n'
    if args.check:
        if not out.is_file():
            raise SystemExit('runtime_env.json missing')
        if out.read_text(encoding='utf-8') != text:
            raise SystemExit('runtime_env.json is stale')
        return 0
    out.write_text(text, encoding='utf-8')
    if args.update_task_metadata:
        update_task_metadata(root, runtime_env)
    print(json.dumps({
        'runtime_env': str(out),
        'languages': runtime_env['languages'],
        'setup_commands': len(runtime_env['setup_commands']),
        'docker_dependency_commands': len(runtime_env['docker']['dependency_commands']),
    }, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
