#!/usr/bin/env python3
from __future__ import annotations
import argparse, csv, html, json, os, re, shlex, shutil, subprocess, sys, zipfile
from pathlib import Path
from typing import Iterable
from urllib import error as urlerror
from urllib import request as urlrequest

SCRIPT_DIR = Path(__file__).resolve().parent
INIT_SCRIPT = SCRIPT_DIR / 'init_task_from_pr.py'
PENDING_MODEL_EVAL = 'PENDING_MODEL_EVAL'
PENDING_DOCKER_EVIDENCE = 'PENDING_DOCKER_EVIDENCE'
PENDING_BATCH_EVIDENCE = 'PENDING_BATCH_EVIDENCE'
PENDING_REVIEW = 'PENDING_REVIEW'
PENDING_PROBLEM_STATEMENT = 'PENDING_PROBLEM_STATEMENT'
NODE_TOOLCHAIN_STAGE = 'FROM node:22-bookworm AS node_toolchain'
NODE_TOOLCHAIN_COPY = 'COPY --from=node_toolchain / /'
OLD_NODE_DEPENDENCIES_CMD = 'node --version && npm --version && git --version'
NODE_DEPENDENCIES_CMD = 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash build-essential && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y --no-install-recommends nodejs && rm -rf /var/lib/apt/lists/* && node --version && npm --version && git --version'
MAX_PASS_TO_PASS_TEST_FILES = 2
HEAVY_PASS_TO_PASS_TOKENS = (
    'benchmark', 'browser', 'chat', 'cloud', 'cuda', 'deepspeed', 'docker',
    'e2e', 'gpu', 'integration', 'live', 'logger', 'loggers', 'model',
    'playwright', 'selenium', 'slow', 'test_utils', 'training', 'ui', 'wave',
    'yaml',
)
EXTERNAL_TEST_PATCH_PATTERNS = (
    ('network fetch', r'\b(?:urlopen|urlretrieve|requests\.(?:get|post|put|delete|patch)|httpx\.(?:get|post|put|delete|patch))\b'),
    ('literal remote URL', r'https?://'),
    ('local service endpoint', r'localhost:\d+|127\.0\.0\.1:\d+'),
    ('runtime API key', r'\b(?:api[_-]?key|API_KEY|Authorization)\b'),
)

LANG_DEFAULTS = {
    'go': {
        'base_image': 'ubuntu:22.04',
        'toolchain_stages': '',
        'toolchain_copy': '',
        'dependencies_cmd': 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash build-essential unzip && ARCH="$(dpkg --print-architecture)" && case "$ARCH" in amd64) GOARCH=amd64 ;; arm64) GOARCH=arm64 ;; *) echo "unsupported Go architecture: $ARCH" >&2; exit 1 ;; esac && curl -fsSL "https://go.dev/dl/go1.25.1.linux-${GOARCH}.tar.gz" -o /tmp/go.tgz && tar -C /usr/local -xzf /tmp/go.tgz && ln -s /usr/local/go/bin/go /usr/local/bin/go && ln -s /usr/local/go/bin/gofmt /usr/local/bin/gofmt && rm -f /tmp/go.tgz && rm -rf /var/lib/apt/lists/* && go env -w GOPROXY=https://proxy.golang.org,direct && go version',
        'before_cmd': 'go mod download',
        'fail_cmd': "go test ./... -count=1",
        'pass_cmd': "go test ./... -count=1",
    },
    'python': {
        'base_image': 'python:3.11-slim-bookworm',
        'toolchain_stages': '',
        'toolchain_copy': '',
        'dependencies_cmd': 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash python3-dev build-essential && rm -rf /var/lib/apt/lists/* && python3 --version && python3 -m pip --version && PIP_NO_CACHE_DIR=1 python3 -m pip install --upgrade pip setuptools wheel pytest',
        'before_cmd': '',
        'fail_cmd': 'python3 -m pytest',
        'pass_cmd': 'python3 -m pytest',
    },
    'javascript': {
        'base_image': 'ubuntu:22.04',
        'toolchain_stages': '',
        'toolchain_copy': '',
        'dependencies_cmd': NODE_DEPENDENCIES_CMD,
        'before_cmd': 'npm install',
        'fail_cmd': 'npm test -- --runInBand',
        'pass_cmd': 'npm test -- --runInBand',
    },
    'typescript': {
        'base_image': 'ubuntu:22.04',
        'toolchain_stages': '',
        'toolchain_copy': '',
        'dependencies_cmd': NODE_DEPENDENCIES_CMD,
        'before_cmd': 'npm install',
        'fail_cmd': 'npm test -- --runInBand',
        'pass_cmd': 'npm test -- --runInBand',
    },
    'java': {
        'base_image': 'ubuntu:22.04',
        'toolchain_stages': '',
        'toolchain_copy': '',
        'dependencies_cmd': 'apt-get update && apt-get install -y --no-install-recommends ca-certificates git bash openjdk-17-jdk-headless maven && rm -rf /var/lib/apt/lists/* && java -version && mvn -version',
        'before_cmd': 'mvn -q -DskipTests dependency:resolve',
        'fail_cmd': 'mvn test',
        'pass_cmd': 'mvn test',
    },
    'rust': {
        'base_image': 'ubuntu:22.04',
        'toolchain_stages': '',
        'toolchain_copy': '',
        'dependencies_cmd': 'apt-get update && apt-get install -y --no-install-recommends ca-certificates curl git bash build-essential pkg-config libssl-dev && curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal && ln -s /root/.cargo/bin/cargo /usr/local/bin/cargo && ln -s /root/.cargo/bin/rustc /usr/local/bin/rustc && rm -rf /var/lib/apt/lists/* && rustc --version && cargo --version',
        'before_cmd': 'cargo fetch',
        'fail_cmd': 'cargo test',
        'pass_cmd': 'cargo test',
    },
}


def run(cmd: list[str], cwd: Path | None = None, allow_fail: bool = False) -> tuple[int, str]:
    print(f"$ {' '.join(shlex.quote(arg) for arg in cmd)}", flush=True)
    process = subprocess.Popen(
        cmd,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=0,
    )
    assert process.stdout is not None

    output_parts: list[str] = []
    line_parts: list[str] = []
    while True:
        ch = process.stdout.read(1)
        if ch == '':
            break
        output_parts.append(ch)
        if ch in {'\r', '\n'}:
            line = ''.join(line_parts).strip()
            if line:
                print(line, flush=True)
            line_parts = []
            continue
        line_parts.append(ch)

    if line_parts:
        line = ''.join(line_parts).strip()
        if line:
            print(line, flush=True)

    return_code = process.wait()
    process.stdout.close()
    output = ''.join(output_parts)
    if return_code and not allow_fail:
        raise RuntimeError(f'command failed: {cmd}\n{output}')
    return return_code, output


def slug_repo(repo: str) -> str:
    return repo.split('/')[-1].lower().replace('_','-')


def pr_number(pr_url: str) -> str:
    m = re.search(r'/pull/(\d+)', pr_url)
    return m.group(1) if m else 'manual'


def _xlsx_cell_value(cell_xml: str, shared: list[str]) -> str:
    t_match = re.search(r't="([^"]+)"', cell_xml)
    typ = t_match.group(1) if t_match else ''
    inline = re.search(r'<is>.*?<t[^>]*>(.*?)</t>.*?</is>', cell_xml, re.S)
    if inline:
        return html.unescape(re.sub(r'<[^>]+>', '', inline.group(1)))
    v = re.search(r'<v>(.*?)</v>', cell_xml, re.S)
    if not v:
        return ''
    raw = html.unescape(v.group(1))
    if typ == 's':
        try:
            return shared[int(raw)]
        except Exception:
            return raw
    return raw


def _read_xlsx(path: Path) -> list[dict]:
    with zipfile.ZipFile(path) as z:
        shared: list[str] = []
        if 'xl/sharedStrings.xml' in z.namelist():
            xml = z.read('xl/sharedStrings.xml').decode('utf-8', 'ignore')
            for si in re.findall(r'<si>(.*?)</si>', xml, re.S):
                texts = re.findall(r'<t[^>]*>(.*?)</t>', si, re.S)
                shared.append(html.unescape(''.join(texts)))
        sheet = z.read('xl/worksheets/sheet1.xml').decode('utf-8', 'ignore')
    rows_raw=[]
    for row_xml in re.findall(r'<row[^>]*>(.*?)</row>', sheet, re.S):
        cells=[]
        for c in re.findall(r'<c[^>]*>.*?</c>', row_xml, re.S):
            ref = re.search(r'r="([A-Z]+)\d+"', c)
            col = ref.group(1) if ref else ''
            idx = 0
            for ch in col:
                idx = idx * 26 + ord(ch) - 64
            cells.append((idx - 1, _xlsx_cell_value(c, shared)))
        if cells:
            max_idx=max(i for i,_ in cells)
            row=['']*(max_idx+1)
            for i,v in cells:
                row[i]=v
            rows_raw.append(row)
    if not rows_raw:
        return []
    headers=[x.strip() for x in rows_raw[0]]
    rows=[]
    for raw in rows_raw[1:]:
        if not any(str(v).strip() for v in raw):
            continue
        rows.append({headers[i]: str(v) if v is not None else '' for i,v in enumerate(raw) if i < len(headers) and headers[i]})
    return rows


def read_candidates(path: Path) -> list[dict]:
    if path.suffix.lower() in {'.xlsx', '.xlsm'}:
        return _read_xlsx(path)
    with path.open(encoding='utf-8-sig') as f:
        return list(csv.DictReader(f))


def selected(row: dict, min_score: int, statuses: set[str]) -> bool:
    try:
        score = int(float(row.get('score') or 0))
    except ValueError:
        score = 0
    status = (row.get('candidate_status') or '').strip()
    if statuses and status not in statuses:
        return False
    return score >= min_score


def row_key(row: dict) -> str:
    return (row.get('candidate_id') or row.get('pr_url') or '').strip()


def filter_selected_rows(rows: list[dict], only: set[str], min_score: int, statuses: set[str]) -> list[dict]:
    if only:
        return [r for r in rows if row_key(r) in only or (r.get('pr_url') or '').strip() in only]
    return [r for r in rows if selected(r, min_score, statuses)]


def github_url(repo: str) -> str:
    return f'https://github.com/{repo}.git'


def ensure_origin(repo_dir: Path, repo: str) -> None:
    url = github_url(repo)
    code, out = run(['git', 'remote', 'get-url', 'origin'], repo_dir, allow_fail=True)
    if code:
        run(['git', 'remote', 'add', 'origin', url], repo_dir)
    elif out.strip() != url:
        run(['git', 'remote', 'set-url', 'origin', url], repo_dir)


def object_exists(repo_dir: Path, commit: str) -> bool:
    if not commit:
        return False
    code, out = run(['git', 'cat-file', '-t', commit], repo_dir, allow_fail=True)
    return code == 0 and out.strip() == 'commit'


def fetch_candidate_refs(repo_dir: Path, pr_url: str, base_commit: str, fix_commit: str) -> None:
    print(f'fetching refs for {pr_url or repo_dir.name}...', flush=True)
    run(['git', 'fetch', '--progress', '--tags', 'origin'], repo_dir, allow_fail=True)
    run(['git', 'fetch', '--progress', 'origin', base_commit, fix_commit], repo_dir, allow_fail=True)
    pr = pr_number(pr_url)
    if pr != 'manual':
        run(['git', 'fetch', '--progress', 'origin', f'+refs/pull/{pr}/head:refs/remotes/origin/pr/{pr}/head'], repo_dir, allow_fail=True)
    missing = [sha for sha in [base_commit, fix_commit] if sha and not object_exists(repo_dir, sha)]
    if missing:
        print(f'missing commits after targeted fetch: {", ".join(missing)}; fetching branches...', flush=True)
        run(['git', 'fetch', '--progress', '--tags', 'origin', '+refs/heads/*:refs/remotes/origin/*'], repo_dir)


def resolve_task_base(repo_dir: Path, base_commit: str, fix_commit: str) -> str:
    if not object_exists(repo_dir, base_commit):
        raise RuntimeError(f'base commit not found after fetch: {base_commit}')
    if not object_exists(repo_dir, fix_commit):
        raise RuntimeError(f'fix commit not found after fetch: {fix_commit}')
    code, _ = run(['git', 'merge-base', '--is-ancestor', base_commit, fix_commit], repo_dir, allow_fail=True)
    if code == 0:
        return base_commit
    code, out = run(['git', 'merge-base', base_commit, fix_commit], repo_dir, allow_fail=True)
    resolved = out.strip()
    if code == 0 and resolved and object_exists(repo_dir, resolved):
        print(f'  adjusted base_commit {base_commit} -> merge-base {resolved}')
        return resolved
    raise RuntimeError(f'base commit is not an ancestor of fix commit and merge-base failed: {base_commit}..{fix_commit}')


def ensure_repo(pkg: Path, repo: str, pr_url: str, base_commit: str, fix_commit: str) -> str:
    repo_dir = pkg / 'repo'
    if repo_dir.exists() and (repo_dir / '.git').exists():
        print(f'reusing existing clone for {repo} at {repo_dir}', flush=True)
        ensure_origin(repo_dir, repo)
    else:
        if repo_dir.exists():
            shutil.rmtree(repo_dir)
        print(f'cloning {repo} into {repo_dir}...', flush=True)
        run(['git', 'clone', '--progress', '--filter=blob:none', '--no-checkout', github_url(repo), str(repo_dir)], pkg.parent)
    fetch_candidate_refs(repo_dir, pr_url, base_commit, fix_commit)
    resolved_base = resolve_task_base(repo_dir, base_commit, fix_commit)
    print(f'checking out base commit {resolved_base} for {repo}', flush=True)
    run(['git','checkout', resolved_base], repo_dir)
    return resolved_base


def generate_patches(pkg: Path, base_commit: str, fix_commit: str) -> tuple[list[str], list[str]]:
    repo_dir = pkg / 'repo'
    changed = changed_files(repo_dir, base_commit, fix_commit)
    test_files = [p for p in changed if is_test_file(p)]
    source_files = [p for p in changed if p not in test_files]

    patch = diff_for_paths(repo_dir, base_commit, fix_commit, source_files)
    test_patch = diff_for_paths(repo_dir, base_commit, fix_commit, test_files)
    (pkg/'patches').mkdir(exist_ok=True)
    (pkg/'patches/gold.patch').write_text(patch, encoding='utf-8')
    (pkg/'patches/test.patch').write_text(test_patch, encoding='utf-8')
    return test_files, source_files


def changed_files(repo_dir: Path, base_commit: str, fix_commit: str) -> list[str]:
    out = run(['git', 'diff', '--name-only', base_commit, fix_commit], repo_dir)[1]
    return [line.strip() for line in out.splitlines() if line.strip()]


def diff_for_paths(repo_dir: Path, base_commit: str, fix_commit: str, paths: list[str]) -> str:
    if not paths:
        return ''
    return run(['git', 'diff', '--binary', base_commit, fix_commit, '--', *paths], repo_dir)[1]


def patch_stats_from_text(patch_text: str) -> dict:
    paths: list[str] = []
    additions = 0
    deletions = 0
    current_path = ''
    for line in patch_text.splitlines():
        if line.startswith('diff --git '):
            parts = line.split()
            current_path = parts[3][2:] if len(parts) >= 4 and parts[3].startswith('b/') else ''
            if current_path and current_path not in paths:
                paths.append(current_path)
            continue
        if line.startswith('+++ ') or line.startswith('--- '):
            continue
        if line.startswith('+'):
            additions += 1
        elif line.startswith('-'):
            deletions += 1
    source_paths = [path for path in paths if is_source_file(path)]
    test_paths = [path for path in paths if is_test_file(path)]
    return {
        'files': len(paths),
        'source_files': len(source_paths),
        'additions': additions,
        'deletions': deletions,
        'total_changed': additions + deletions,
        'paths': paths,
        'source_paths': source_paths,
        'test_paths': test_paths,
    }


def combined_patch_stats(gold_patch: str, test_patch: str) -> dict:
    gold = patch_stats_from_text(gold_patch)
    test = patch_stats_from_text(test_patch)
    return {
        'source': 'actual_generated_patches',
        'files': gold['files'] + test['files'],
        'source_files': gold['source_files'] + test['source_files'],
        'insertions': gold['additions'] + test['additions'],
        'deletions': gold['deletions'] + test['deletions'],
        'total_changed': gold['total_changed'] + test['total_changed'],
        'gold_patch_files': gold['files'],
        'gold_source_files': gold['source_files'],
        'gold_insertions': gold['additions'],
        'gold_deletions': gold['deletions'],
        'gold_total_changed': gold['total_changed'],
        'test_patch_files': test['files'],
        'test_insertions': test['additions'],
        'test_deletions': test['deletions'],
        'test_total_changed': test['total_changed'],
        'gold_paths': gold['paths'],
        'test_paths': test['paths'],
    }


def current_file_from_diff_line(line: str) -> str | None:
    if not line.startswith('diff --git '):
        return None
    parts = line.split()
    if len(parts) < 4:
        return None
    return parts[3].removeprefix('b/')


def extract_test_ids_from_patch(test_patch: str) -> list[str]:
    test_ids: list[str] = []
    current_file: str | None = None
    current_py_class: str | None = None
    pending_rust_test = False
    pending_rust_macro_test = False
    py_test_re = re.compile(r'^\+\s*def\s+(test_[A-Za-z0-9_]+)\s*\(')
    py_class_re = re.compile(r'^\+\s*class\s+(Test[A-Za-z0-9_]+)\b')
    go_test_re = re.compile(r'^\+\s*func\s+(Test[A-Za-z0-9_]+)\s*\(')
    rust_fn_re = re.compile(r'^\+\s*fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(')
    rust_macro_start_re = re.compile(r'^\+\s*([A-Za-z_][A-Za-z0-9_]*)!\s*\(')
    rust_macro_name_re = re.compile(r'^\+\s*([A-Za-z_][A-Za-z0-9_]*)\s*,')
    js_test_re = re.compile(r'^\+\s*(?:test|it)\s*\(\s*[\'"]([^\'"]+)')

    for line in test_patch.splitlines():
        path = current_file_from_diff_line(line)
        if path:
            current_file = path
            current_py_class = None
            pending_rust_test = False
            pending_rust_macro_test = False
            continue
        if current_file is None:
            continue
        if match := py_class_re.match(line):
            current_py_class = match.group(1)
            continue
        if match := py_test_re.match(line):
            test_name = match.group(1)
            if current_py_class:
                test_ids.append(f'{current_file}::{current_py_class}::{test_name}')
            else:
                test_ids.append(f'{current_file}::{test_name}')
            continue
        if match := go_test_re.match(line):
            test_ids.append(f'{current_file}::{match.group(1)}')
            continue
        if current_file.endswith('.rs') and line.startswith('+') and '#[test]' in line:
            pending_rust_test = True
            continue
        if current_file.endswith('.rs') and pending_rust_test and (match := rust_fn_re.match(line)):
            test_ids.append(f'{current_file}::{match.group(1)}')
            pending_rust_test = False
            continue
        if current_file.endswith('.rs') and (match := rust_macro_start_re.match(line)):
            macro_name = match.group(1).lower()
            pending_rust_macro_test = macro_name.endswith('test') or 'test' in macro_name
            continue
        if current_file.endswith('.rs') and pending_rust_macro_test and (match := rust_macro_name_re.match(line)):
            test_ids.append(f'{current_file}::{match.group(1)}')
            pending_rust_macro_test = False
            continue
        if match := js_test_re.match(line):
            label = match.group(1).strip().replace('\n', ' ')
            test_ids.append(f'{current_file}::{label}')

    return list(dict.fromkeys(test_ids))


def is_test_file(path: str) -> bool:
    lower = path.lower()
    parts = lower.split('/')
    name = parts[-1]
    return (
        'test' in parts
        or 'tests' in parts
        or '__tests__' in parts
        or any(part in {'src/test', 'src/androidtest'} for part in ('/'.join(parts[:idx + 1]) for idx in range(len(parts))))
        or any(part.endswith('test') and part not in {'latest'} for part in parts)
        or name.endswith((
            '_test.go', '_test.py',
            '.test.js', '.test.jsx', '.test.ts', '.test.tsx',
            '.spec.js', '.spec.jsx', '.spec.ts', '.spec.tsx',
            'test.java', 'tests.java', 'test.kt', 'tests.kt',
            'test.cs', 'tests.cs', 'spec.rb', '_spec.rb',
        ))
        or name.startswith('test_')
    )


def is_source_file(path: str) -> bool:
    return not path.lower().endswith(('.md', '.txt', '.json', '.yaml', '.yml', '.lock', '.sum'))


def shell_join(commands: list[str]) -> str:
    return ' && '.join(f'({cmd})' for cmd in commands if cmd)


def quote_paths(paths: list[str]) -> str:
    return ' '.join(shlex.quote(p) for p in paths)


def path_has_heavy_pass_to_pass_signal(path: str) -> bool:
    parts = [part.lower() for part in Path(path).parts]
    stem = Path(path).stem.lower()
    return any(token in parts or token in stem for token in HEAVY_PASS_TO_PASS_TOKENS)


def estimate_test_count(path: Path) -> int:
    try:
        text = path.read_text(encoding='utf-8', errors='ignore')
    except OSError:
        return 1000
    patterns = (
        r'(?m)^\s*def\s+test_',
        r'(?m)^\s*func\s+Test[A-Za-z0-9_]+\s*\(',
        r'(?m)^\s*(?:test|it)\s*\(',
        r'(?m)^\s*@Test\b',
        r'(?m)^\s*#\[test\]',
    )
    count = sum(len(re.findall(pattern, text)) for pattern in patterns)
    if '@pytest.mark.parametrize' in text:
        count += len(re.findall(r'(?m)^\s*\([^#\n]+,\s*[^#\n]*\),?\s*$', text))
    return count if count > 0 else max(1, min(1000, len(text.splitlines()) // 50))


def nearby_test_files(
    repo_dir: Path,
    changed_files: list[str],
    suffixes: tuple[str, ...],
    max_files: int = MAX_PASS_TO_PASS_TEST_FILES,
) -> list[str]:
    changed = set(changed_files)
    changed_dirs = {Path(path).parent for path in changed_files}
    changed_top_dirs = {Path(path).parts[0] for path in changed_files if Path(path).parts}
    candidates: list[tuple[int, int, int, str]] = []
    if not repo_dir.is_dir():
        return []

    for path in repo_dir.rglob('*'):
        if not path.is_file():
            continue
        try:
            rel = path.relative_to(repo_dir).as_posix()
        except ValueError:
            continue
        if rel in changed or not rel.endswith(suffixes) or not is_test_file(rel):
            continue
        parts = set(Path(rel).parts)
        if {'node_modules', 'vendor', 'target', 'dist', 'build', '.git'} & parts:
            continue
        if path_has_heavy_pass_to_pass_signal(rel):
            continue
        parent = Path(rel).parent
        top_dir = Path(rel).parts[0] if Path(rel).parts else ''
        if parent in changed_dirs:
            locality = 0
        elif top_dir and top_dir in changed_top_dirs:
            locality = 1
        elif any(parent.is_relative_to(changed_dir) or changed_dir.is_relative_to(parent) for changed_dir in changed_dirs):
            locality = 2
        else:
            locality = 3
        candidates.append((locality, estimate_test_count(path), len(Path(rel).parts), rel))

    return [rel for _, _, _, rel in sorted(candidates)[:max_files]]


def nearest_package_dir(repo_dir: Path, path: str) -> str:
    cur = Path(path).parent
    while str(cur) not in {'', '.'}:
        if (repo_dir / cur / 'package.json').exists():
            return cur.as_posix()
        cur = cur.parent
    return '.'


def docker_npm_install_dir(pkg: Path) -> str:
    repo_dir = pkg / 'repo'
    task_path = pkg / 'task.json'
    task = json.loads(task_path.read_text(encoding='utf-8')) if task_path.is_file() else {}
    command_text = '\n'.join(
        str(value)
        for value in [
            task.get('before_repo_set_cmd'),
            *(task.get('fail_to_pass') if isinstance(task.get('fail_to_pass'), list) else []),
            *(task.get('pass_to_pass') if isinstance(task.get('pass_to_pass'), list) else []),
        ]
        if value
    )
    for match in re.finditer(r'(?:^|[(&;])\s*cd\s+([^\s;&|()]+)\s+&&\s+(?:npm|pnpm|yarn)\b', command_text, flags=re.M):
        rel = match.group(1).strip().strip('"\'').removeprefix('./')
        if not rel.startswith('/') and '..' not in Path(rel).parts and (repo_dir / rel / 'package.json').is_file():
            return '/workspace/repo' if rel in {'', '.'} else f'/workspace/repo/{rel}'
    selected = task.get('selected_test_files_to_run')
    if isinstance(selected, list):
        for item in selected:
            if isinstance(item, str):
                rel = nearest_package_dir(repo_dir, item)
                if rel != '.':
                    return f'/workspace/repo/{rel}'
    if (repo_dir / 'package.json').is_file():
        return '/workspace/repo'
    package_jsons = [path for path in repo_dir.rglob('package.json') if 'node_modules' not in path.parts] if repo_dir.is_dir() else []
    if len(package_jsons) == 1:
        rel = package_jsons[0].parent.relative_to(repo_dir).as_posix()
        return '/workspace/repo' if rel == '.' else f'/workspace/repo/{rel}'
    for common in ('web', 'frontend', 'client', 'app'):
        if (repo_dir / common / 'package.json').is_file():
            return f'/workspace/repo/{common}'
    return '/workspace/repo'


def nearest_go_module_dir(repo_dir: Path, path: str) -> str:
    cur = Path(path).parent
    while str(cur) not in {'', '.'}:
        if (repo_dir / cur / 'go.mod').exists():
            return cur.as_posix()
        cur = cur.parent
    return '.' if (repo_dir / 'go.mod').exists() else '.'


def go_test_command(repo_dir: Path, module_dir: str, files: list[str]) -> str:
    package_dirs = sorted({str(Path(p).parent.relative_to(module_dir)) if module_dir != '.' else str(Path(p).parent) for p in files})
    packages = []
    for package_dir in package_dirs:
        if package_dir in {'', '.'}:
            packages.append('./...')
        else:
            packages.append('./' + package_dir)
    prefix = f'cd {shlex.quote(module_dir)} && ' if module_dir != '.' else ''
    return f'{prefix}go test {quote_paths(packages)} -count=1'


def go_test_names(repo_dir: Path, path: str) -> list[str]:
    try:
        text = (repo_dir / path).read_text(encoding='utf-8', errors='ignore')
    except OSError:
        return []
    names = re.findall(r'(?m)^\s*func\s+(Test[A-Za-z0-9_]+)\s*\(\s*t\s+\*testing\.T\s*\)', text)
    return list(dict.fromkeys(names))


def go_pass_to_pass_command(repo_dir: Path, module_dir: str, files: list[str]) -> str:
    candidates = nearby_test_files(repo_dir, files, ('_test.go',), max_files=MAX_PASS_TO_PASS_TEST_FILES)
    if not candidates:
        return go_test_command(repo_dir, module_dir, files)

    by_package: dict[str, list[str]] = {}
    for path in candidates:
        if nearest_go_module_dir(repo_dir, path) != module_dir:
            continue
        package_dir = str(Path(path).parent.relative_to(module_dir)) if module_dir != '.' else str(Path(path).parent)
        package = './...' if package_dir in {'', '.'} else './' + package_dir
        by_package.setdefault(package, []).extend(go_test_names(repo_dir, path))

    commands: list[str] = []
    prefix = f'cd {shlex.quote(module_dir)} && ' if module_dir != '.' else ''
    for package, names in sorted(by_package.items()):
        selected = sorted(dict.fromkeys(names))[:3]
        if selected:
            pattern = '^(' + '|'.join(re.escape(name) for name in selected) + ')$'
            commands.append(f'{prefix}go test {shlex.quote(package)} -run {shlex.quote(pattern)} -count=1')
    return shell_join(commands) if commands else go_test_command(repo_dir, module_dir, files)


def nearest_maven_module_dir(repo_dir: Path, path: str) -> str:
    cur = Path(path).parent
    while str(cur) not in {'', '.'}:
        if (repo_dir / cur / 'pom.xml').exists():
            return cur.as_posix()
        cur = cur.parent
    return '.'


def java_test_class(path: str) -> str:
    return Path(path).stem


def java_test_command(repo_dir: Path, files: list[str]) -> str:
    by_module: dict[str, list[str]] = {}
    for path in files:
        by_module.setdefault(nearest_maven_module_dir(repo_dir, path), []).append(path)
    commands: list[str] = []
    for module_dir, paths in sorted(by_module.items()):
        prefix = f'cd {shlex.quote(module_dir)} && ' if module_dir != '.' else ''
        classes = ','.join(sorted(java_test_class(path) for path in paths))
        commands.append(f'{prefix}mvn test -Dtest={shlex.quote(classes)}')
    return shell_join(commands)


def nearest_cargo_package_dir(repo_dir: Path, path: str) -> str:
    cur = Path(path).parent
    while str(cur) not in {'', '.'}:
        if (repo_dir / cur / 'Cargo.toml').exists():
            return cur.as_posix()
        cur = cur.parent
    return '.'


def cargo_package_name(manifest: Path) -> str | None:
    try:
        text = manifest.read_text(encoding='utf-8', errors='ignore')
    except OSError:
        return None
    in_package = False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        if stripped.startswith('['):
            in_package = stripped == '[package]'
            continue
        if in_package:
            match = re.match(r'name\s*=\s*["\']([^"\']+)["\']', stripped)
            if match:
                return match.group(1)
    return None


def rust_test_target(path: str, package_dir: str) -> str | None:
    rel = Path(path)
    if package_dir != '.':
        try:
            rel = rel.relative_to(package_dir)
        except ValueError:
            return None
    parts = rel.parts
    if len(parts) >= 2 and parts[0] == 'tests':
        target = Path(parts[1]).stem
        return target or None
    return None


def rust_unit_test_filter(path: str, package_dir: str) -> str | None:
    rel = Path(path)
    if package_dir != '.':
        try:
            rel = rel.relative_to(package_dir)
        except ValueError:
            return None
    parts = rel.parts
    if len(parts) >= 2 and parts[0] == 'src':
        module_parts = parts[1:]
        return '::'.join(Path(part).stem if i == len(module_parts) - 1 else part for i, part in enumerate(module_parts))
    return None


def test_id_file(test_id: str) -> str:
    return test_id.split('::', 1)[0]


def test_ids_for_file(test_ids: list[str], path: str) -> list[str]:
    prefix = f'{path}::'
    return [test_id[len(prefix):] for test_id in test_ids if test_id.startswith(prefix)]


def rust_existing_test_candidates(repo_dir: Path, path: str, excluded: set[str]) -> list[str]:
    test_path = repo_dir / path
    try:
        text = test_path.read_text(encoding='utf-8', errors='ignore')
    except OSError:
        return []

    candidates: list[tuple[int, int, str]] = []
    pending_test_attr = False
    for index, line in enumerate(text.splitlines(), start=1):
        if '#[test]' in line:
            pending_test_attr = True
            continue
        match = re.match(r'\s*(?:pub\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(', line)
        if pending_test_attr and match:
            candidates.append((index, index, match.group(1)))
            pending_test_attr = False
            continue
        if line.strip() and not line.lstrip().startswith('#'):
            pending_test_attr = False

    macro_re = re.compile(r'(?m)^\s*([A-Za-z_][A-Za-z0-9_]*)!\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*,')
    for match in macro_re.finditer(text):
        macro_name, test_name = match.group(1), match.group(2)
        macro_lower = macro_name.lower()
        if not (macro_lower.endswith('test') or 'test' in macro_lower):
            continue
        start = text.count('\n', 0, match.start()) + 1
        end = text.find('\n);', match.end())
        end_line = text.count('\n', 0, end if end != -1 else match.end()) + 1
        body = text[match.start(): end if end != -1 else match.end()]
        candidates.append((start, end_line, test_name))

    def is_low_risk(start: int, end: int, name: str) -> bool:
        lower_name = name.lower()
        if name in excluded:
            return False
        body_lines = text.splitlines()[max(start - 1, 0):end]
        body = '\n'.join(body_lines).lower()
        risky_tokens = (
            'cheatcode', 'fork', 'network', 'rpc', 'http://', 'https://', 'import',
            'live_log', 'fetch_interface', 'external', 'docker', 'socket',
        )
        if 'init = true' in body:
            return False
        return not any(token in lower_name or token in body for token in risky_tokens)

    unique: list[tuple[int, int, str]] = []
    seen: set[str] = set()
    for candidate in sorted(candidates):
        if candidate[2] not in seen and is_low_risk(*candidate):
            unique.append(candidate)
            seen.add(candidate[2])

    scored = sorted(unique, key=lambda item: (item[1] - item[0], item[0], item[2]))
    return [name for _, _, name in scored[:3]]


def rust_integration_test_paths(repo_dir: Path, package_dir: str) -> list[str]:
    package_root = repo_dir if package_dir == '.' else repo_dir / package_dir
    tests_root = package_root / 'tests'
    if not tests_root.is_dir():
        return []
    paths: list[str] = []
    for path in sorted(tests_root.rglob('*.rs')):
        try:
            rel = path.relative_to(repo_dir).as_posix()
        except ValueError:
            continue
        paths.append(rel)
    return paths


def rust_command(package: str | None, target: str | None = None, test_filter: str | None = None) -> str:
    parts = ['cargo', 'test']
    if package:
        parts.extend(['-p', package])
    if target:
        parts.extend(['--test', target])
    if test_filter:
        parts.append(test_filter)
    return ' '.join(shlex.quote(part) for part in parts)


def rust_pass_to_pass_commands(
    repo_dir: Path,
    test_files: list[str],
    test_ids: list[str] | None = None,
) -> list[str]:
    package_dirs = sorted({
        nearest_cargo_package_dir(repo_dir, path)
        for path in test_files
        if path.endswith('.rs')
    })
    commands: list[str] = []
    emitted: set[str] = set()
    changed = set(test_files)

    for package_dir in package_dirs:
        package_start = len(commands)
        manifest = repo_dir / package_dir / 'Cargo.toml' if package_dir != '.' else repo_dir / 'Cargo.toml'
        package = cargo_package_name(manifest)
        changed_rs = [path for path in test_files if path.endswith('.rs') and nearest_cargo_package_dir(repo_dir, path) == package_dir]
        changed_targets = {
            target for target in (rust_test_target(path, package_dir) for path in changed_rs) if target
        }
        has_unit_test_change = any(rust_test_target(path, package_dir) is None for path in changed_rs)

        for path in changed_rs:
            target = rust_test_target(path, package_dir)
            if not target:
                continue
            if not test_ids:
                continue
            selected = set(test_ids_for_file(test_ids or [], path))
            for test_filter in rust_existing_test_candidates(repo_dir, path, selected):
                command = rust_command(package, target, test_filter)
                if command not in emitted:
                    commands.append(command)
                    emitted.add(command)
            if len(commands) > package_start:
                continue

        for path in rust_integration_test_paths(repo_dir, package_dir):
            if path in changed:
                continue
            target = rust_test_target(path, package_dir)
            if not target or target in changed_targets:
                continue
            for test_filter in rust_existing_test_candidates(repo_dir, path, set()):
                command = rust_command(package, target, test_filter)
                if command not in emitted:
                    commands.append(command)
                    emitted.add(command)
                break
            if len(commands) >= 3:
                break

        if len(commands) == package_start and has_unit_test_change:
            command = rust_command(package)
            commands.append(command)
            emitted.add(command)
        elif len(commands) == package_start:
            command = rust_command(package)
            commands.append(command)
            emitted.add(command)

    return commands


def rust_test_commands(
    repo_dir: Path,
    test_files: list[str],
    include_filter: bool,
    test_ids: list[str] | None = None,
    prefer_existing: bool = False,
) -> list[str]:
    grouped: dict[tuple[str, str | None, str | None], set[str | None]] = {}
    for path in [p for p in test_files if p.endswith('.rs')]:
        package_dir = nearest_cargo_package_dir(repo_dir, path)
        manifest = repo_dir / package_dir / 'Cargo.toml' if package_dir != '.' else repo_dir / 'Cargo.toml'
        package = cargo_package_name(manifest)
        target = rust_test_target(path, package_dir)
        unit_filter = rust_unit_test_filter(path, package_dir)
        selected = [value for value in test_ids_for_file(test_ids or [], path) if value]
        filters = rust_existing_test_candidates(repo_dir, path, set(selected)) if include_filter and prefer_existing else selected
        if include_filter and not filters and test_ids and not prefer_existing:
            filters = rust_existing_test_candidates(repo_dir, path, set(selected))
        if include_filter and filters:
            for test_filter in filters:
                grouped.setdefault((package_dir, package, target), set()).add(test_filter)
        else:
            grouped.setdefault((package_dir, package, target), set()).add(unit_filter if target is None else None)

    commands: list[str] = []
    for (_package_dir, package, target), filters in sorted(grouped.items()):
        selected_filters = sorted(filter(None, filters))
        filters_to_emit = selected_filters if include_filter and selected_filters else [None]
        for test_filter in filters_to_emit:
            commands.append(rust_command(package, target, test_filter))
    return commands


def default_go_test_command(repo_dir: Path) -> str:
    if (repo_dir / 'go.mod').exists():
        return 'go test ./... -count=1'
    module_dirs = sorted(p.parent.relative_to(repo_dir).as_posix() for p in repo_dir.glob('*/go.mod'))
    if module_dirs:
        return shell_join([f'cd {shlex.quote(d)} && go test ./... -count=1' for d in module_dirs])
    return LANG_DEFAULTS['go']['pass_cmd']


def npm_test_command(repo_dir: Path, package_dir: str, files: list[str]) -> str:
    package_json = repo_dir / package_dir / 'package.json'
    script = npm_script_for_files(package_json, package_dir, files)
    rel_files = [str(Path(p).relative_to(package_dir)) if package_dir != '.' else p for p in files]
    prefix = f'cd {shlex.quote(package_dir)} && ' if package_dir != '.' else ''
    test_cmd = f'{prefix}npm run {script} -- {quote_paths(rel_files)}'
    return shell_join([*playwright_webserver_build_commands(repo_dir, package_dir), test_cmd])


def npm_package_test_command(repo_dir: Path, package_dir: str) -> str:
    package_json = repo_dir / package_dir / 'package.json'
    script = npm_script_for_files(package_json, package_dir, [])
    prefix = f'cd {shlex.quote(package_dir)} && ' if package_dir != '.' else ''
    test_cmd = f'{prefix}npm run {script}'
    return shell_join([*playwright_webserver_build_commands(repo_dir, package_dir), test_cmd])


def playwright_webserver_build_commands(repo_dir: Path, package_dir: str) -> list[str]:
    package_root = repo_dir / package_dir
    config = next((p for p in [package_root / 'playwright.config.js', package_root / 'playwright.config.ts'] if p.exists()), None)
    if not config:
        return []
    text = config.read_text(encoding='utf-8', errors='ignore')
    commands = []
    for command_text in re.findall(r'command:\s*[\'"]([^\'"]+)[\'"]', text):
        for token in re.findall(r'(?:^|\s|=)(\.\.?/[^\s>]+)', command_text):
            target = (package_root / token).resolve()
            if target.exists():
                continue
            source_dir = target.parent
            if (source_dir / 'main.go').exists():
                cmd = make_target_build_command(repo_dir, target, source_dir)
                if not cmd:
                    out_rel = target.relative_to(repo_dir).as_posix()
                    src_rel = source_dir.relative_to(repo_dir).as_posix()
                    cmd = f'go build -o {shlex.quote(out_rel)} ./{shlex.quote(src_rel)}'
                if cmd not in commands:
                    commands.append(cmd)
    return commands


def make_target_build_command(repo_dir: Path, target: Path, source_dir: Path) -> str | None:
    repo_dir = repo_dir.resolve()
    makefiles = makefiles_for_target(repo_dir, target)
    candidate_names = candidate_make_target_names(target)
    best: tuple[int, int, int, Path, str] | None = None
    for makefile in makefiles:
        make_dir = makefile.parent
        targets = parse_makefile_targets(makefile)
        if not targets:
            continue
        try:
            target_rel_make = target.relative_to(make_dir).as_posix()
        except ValueError:
            target_rel_make = ''
        try:
            target_rel_repo = target.relative_to(repo_dir).as_posix()
        except ValueError:
            target_rel_repo = ''
        try:
            source_rel_make = source_dir.relative_to(make_dir).as_posix()
        except ValueError:
            source_rel_make = ''
        for index, (name, body) in enumerate(targets.items()):
            score = 0
            if name in candidate_names:
                score += 100
            if name.startswith('build-') or name.endswith('-build'):
                score += 20
            if name.startswith('test'):
                score -= 40
            if name in {'build', 'compile', 'prepare'}:
                score -= 40
            if name.startswith('clean'):
                score -= 80
            if target_rel_make and target_rel_make in body:
                score += 100
            if target_rel_repo and target_rel_repo != target_rel_make and target_rel_repo in body:
                score += 80
            if source_rel_make and source_rel_make in body:
                score += 20
            if score <= 0:
                continue
            key = (score, -len(name), -index, makefile, name)
            if best is None or key[:3] > best[:3]:
                best = key
    if best is None:
        return None
    _, _, _, makefile, target_name = best
    make_dir = makefile.parent
    if make_dir == repo_dir:
        return f'make {shlex.quote(target_name)}'
    return f'make -C {shlex.quote(make_dir.relative_to(repo_dir).as_posix())} {shlex.quote(target_name)}'


def makefiles_for_target(repo_dir: Path, target: Path) -> list[Path]:
    names = ('GNUmakefile', 'makefile', 'Makefile')
    found: list[Path] = []
    cur = target.parent
    while True:
        for name in names:
            path = cur / name
            if path.exists() and path not in found:
                found.append(path)
        if cur == repo_dir or repo_dir not in cur.parents:
            break
        cur = cur.parent
    for name in names:
        path = repo_dir / name
        if path.exists() and path not in found:
            found.append(path)
    return found


def parse_makefile_targets(makefile: Path) -> dict[str, str]:
    try:
        lines = makefile.read_text(encoding='utf-8', errors='ignore').splitlines()
    except OSError:
        return {}
    targets: dict[str, str] = {}
    current: list[str] = []
    body: list[str] = []

    def flush() -> None:
        if not current:
            return
        text = '\n'.join(body)
        for name in current:
            targets.setdefault(name, text)

    for line in lines:
        match = re.match(r'^([A-Za-z0-9_./%+$@-][^:=#]*):(?!=)(.*)$', line)
        if match and not line.startswith('\t'):
            flush()
            names = []
            for raw in match.group(1).split():
                if raw.startswith('.') or '%' in raw or '$' in raw or '=' in raw:
                    continue
                names.append(raw)
            current = names
            body = [line]
        elif current and line.startswith('\t'):
            body.append(line.strip())
        elif current and (not line.strip() or line.lstrip().startswith('#')):
            body.append(line)
        elif current:
            flush()
            current = []
            body = []
    flush()
    return targets


def candidate_make_target_names(target: Path) -> set[str]:
    names: set[str] = set()
    parts = [target.name, target.stem, target.parent.name]
    for part in parts:
        token = re.sub(r'[^A-Za-z0-9]+', '-', part).strip('-')
        if not token:
            continue
        variants = {token, token.lower()}
        for variant in variants:
            names.add(variant)
            names.add(f'build-{variant}')
            names.add(f'compile-{variant}')
            names.add(f'prepare-{variant}')
    return names


def npm_script_for_files(package_json: Path, package_dir: str, files: list[str]) -> str:
    try:
        scripts = json.loads(package_json.read_text(encoding='utf-8')).get('scripts', {})
    except Exception:
        scripts = {}
    rel_files = [str(Path(p).relative_to(package_dir)) if package_dir != '.' else p for p in files]
    e2e_signal = (
        any('/e2e/' in ('/' + p.replace('\\', '/')) or p.replace('\\', '/').startswith('e2e/') for p in rel_files)
        or (package_json.parent / 'playwright.config.js').exists()
        or (package_json.parent / 'playwright.config.ts').exists()
    )
    if e2e_signal and 'test:e2e' in scripts:
        return 'test:e2e'
    for candidate in ('test:run', 'test', 'test:e2e'):
        if candidate in scripts:
            return candidate
    for name in scripts:
        if name.startswith('test'):
            return name
    return 'test'


def test_command_for_files(pkg: Path, row: dict, test_files: list[str], test_ids: list[str] | None = None) -> str:
    repo_dir = pkg / 'repo'
    commands: list[str] = []

    go_by_module: dict[str, list[str]] = {}
    for path in [p for p in test_files if p.endswith('_test.go')]:
        go_by_module.setdefault(nearest_go_module_dir(repo_dir, path), []).append(path)
    for module_dir, paths in sorted(go_by_module.items()):
        commands.append(go_test_command(repo_dir, module_dir, paths))

    py_files = [p for p in test_files if p.endswith('.py')]
    if py_files:
        commands.append('python -m pytest ' + quote_paths(py_files))

    js_exts = ('.test.js', '.test.jsx', '.test.ts', '.test.tsx', '.spec.js', '.spec.jsx', '.spec.ts', '.spec.tsx')
    js_files = [p for p in test_files if p.endswith(js_exts)]
    js_by_package: dict[str, list[str]] = {}
    for path in js_files:
        js_by_package.setdefault(nearest_package_dir(repo_dir, path), []).append(path)
    for package_dir, paths in sorted(js_by_package.items()):
        commands.append(npm_test_command(repo_dir, package_dir, paths))

    java_files = [p for p in test_files if p.endswith('.java')]
    if java_files:
        commands.append(java_test_command(repo_dir, java_files))

    commands.extend(rust_test_commands(repo_dir, test_files, include_filter=True, test_ids=test_ids))

    if commands:
        return shell_join(commands)

    lang = (row.get('primary_language') or '').lower()
    if lang == 'go':
        return LANG_DEFAULTS['go']['fail_cmd']
    if lang in {'javascript', 'typescript'}:
        return LANG_DEFAULTS['javascript']['fail_cmd']
    if lang == 'python':
        return LANG_DEFAULTS['python']['fail_cmd']
    if lang == 'java':
        return LANG_DEFAULTS['java']['fail_cmd']
    if lang == 'rust':
        return LANG_DEFAULTS['rust']['fail_cmd']
    return LANG_DEFAULTS['go']['fail_cmd']


def pass_to_pass_command_for_files(pkg: Path, row: dict, test_files: list[str], test_ids: list[str] | None = None) -> str:
    repo_dir = pkg / 'repo'
    commands: list[str] = []

    go_by_module: dict[str, list[str]] = {}
    for path in [p for p in test_files if p.endswith('_test.go')]:
        go_by_module.setdefault(nearest_go_module_dir(repo_dir, path), []).append(path)
    for module_dir, paths in sorted(go_by_module.items()):
        commands.append(go_pass_to_pass_command(repo_dir, module_dir, paths))

    py_files = [p for p in test_files if p.endswith('.py')]
    if py_files:
        candidates = nearby_test_files(repo_dir, py_files, ('.py',))
        if candidates:
            commands.append('python -m pytest ' + quote_paths(candidates))
        else:
            py_dirs = sorted({str(Path(p).parent) for p in py_files})
            ignores = ' '.join('--ignore=' + shlex.quote(path) for path in sorted(py_files))
            commands.append('python -m pytest ' + quote_paths(py_dirs) + (f' {ignores}' if ignores else ''))

    js_exts = ('.test.js', '.test.jsx', '.test.ts', '.test.tsx', '.spec.js', '.spec.jsx', '.spec.ts', '.spec.tsx')
    js_by_package: dict[str, list[str]] = {}
    for path in [p for p in test_files if p.endswith(js_exts)]:
        js_by_package.setdefault(nearest_package_dir(repo_dir, path), []).append(path)
    for package_dir, paths in sorted(js_by_package.items()):
        candidates = [
            path for path in nearby_test_files(repo_dir, paths, js_exts)
            if nearest_package_dir(repo_dir, path) == package_dir
        ]
        if candidates:
            commands.append(npm_test_command(repo_dir, package_dir, candidates))
        else:
            commands.append(npm_package_test_command(repo_dir, package_dir))

    java_files = [p for p in test_files if p.endswith('.java')]
    if java_files:
        candidates = nearby_test_files(repo_dir, java_files, ('.java',))
        if candidates:
            commands.append(java_test_command(repo_dir, candidates))
        else:
            commands.append(java_test_command(repo_dir, java_files))

    commands.extend(rust_pass_to_pass_commands(repo_dir, test_files, test_ids))

    if commands:
        return shell_join(commands)
    return default_test_command(pkg, row)


def default_test_command(pkg: Path, row: dict) -> str:
    lang = (row.get('primary_language') or 'go').lower()
    if lang == 'go':
        return default_go_test_command(pkg / 'repo')
    return LANG_DEFAULTS.get(lang, LANG_DEFAULTS['go'])['pass_cmd']


def language_from_selected_tests(test_files: list[str]) -> str | None:
    suffix_map = [
        (('_test.go',), 'go'),
        (('.py',), 'python'),
        (('.java',), 'java'),
        (('.rs',), 'rust'),
        (('.test.ts', '.test.tsx', '.spec.ts', '.spec.tsx'), 'typescript'),
        (('.test.js', '.test.jsx', '.spec.js', '.spec.jsx'), 'javascript'),
    ]
    for suffixes, language in suffix_map:
        if any(path.endswith(suffixes) for path in test_files):
            return language
    return None


def derive_requirements_text(row: dict) -> str:
    issue_text = (row.get('problem_statement') or '').strip()
    lines = [
        'The implementation must satisfy the user-visible behavior described in the problem statement and source issue.',
    ]
    if issue_text:
        excerpt = issue_text[:4000]
        lines.extend(['', 'Issue evidence excerpt:', excerpt])
    return '\n'.join(lines)


def derive_interface_text() -> str:
    return 'No additional model-facing public interface requirements were identified from the source issue.'


def summarize_test_files(test_files: list[str]) -> str:
    if not test_files:
        return 'No test files were changed in the source PR.'
    shown = '\n'.join(f'- `{p}`' for p in test_files[:12])
    extra = f'\n- ... and {len(test_files) - 12} more' if len(test_files) > 12 else ''
    return shown + extra


def summarize_source_files(source_files: list[str]) -> str:
    if not source_files:
        return 'No non-test source files were changed in the source PR.'
    shown = '\n'.join(f'- `{p}`' for p in source_files[:12])
    extra = f'\n- ... and {len(source_files) - 12} more' if len(source_files) > 12 else ''
    return shown + extra


def assess_test_patch(
    test_patch: str,
    test_files: list[str],
    gold_patch: str = '',
    issue_text: str = '',
) -> dict:
    lines = test_patch.splitlines()
    added = ['+' + line for line in added_lines_from_patch(test_patch)]
    removed = ['-' + line for line in removed_lines_from_patch(test_patch)]
    changed = added + removed
    changed_text = '\n'.join(changed)
    test_patch_paths = changed_paths_from_patch(test_patch)
    non_test_patch_paths = [path for path in test_patch_paths if not is_test_support_file(path)]

    assertion_markers = (
        'assert', 'expect(', 'should', 'throws', 'fail(', 'assertThat(', 'assertEquals(',
        'assertTrue(', 'assertFalse(', 'assertNull(', 'assertNotNull(', 'pytest.raises',
    )
    skip_markers = (
        '@disabled', '@ignore', '@flaky', '@j2ktincompatible', '@gwtincompatible',
        'skip(', 'xfail', '@suppresswarnings',
    )
    structural_prefixes = ('import ', 'package ', 'using ', 'namespace ', '@', '//', '/*', '*')

    assertion_lines = sum(1 for line in changed if any(marker in line.lower() for marker in assertion_markers))
    skip_lines = sum(1 for line in changed if any(marker in line.lower() for marker in skip_markers))
    structural_lines = sum(
        1 for line in changed
        if line[1:].strip().startswith(structural_prefixes) or not line[1:].strip()
    )
    file_headers = sum(1 for line in lines if line.startswith('diff --git '))
    hunks = sum(1 for line in lines if line.startswith('@@'))

    risks: list[str] = []
    strengths: list[str] = []

    if not changed:
        risks.append('test.patch 为空，没有生成任何测试改动。')
    else:
        strengths.append(f'覆盖 {len(test_files)} 个测试文件，{file_headers} 个 diff 文件段，{hunks} 个 hunk。')

    if changed and assertion_lines == 0:
        risks.append('未检测到明显断言/异常校验语句，测试可能更偏结构性改动。')
    elif changed:
        strengths.append(f'检测到约 {assertion_lines} 行断言/校验相关改动。')

    if changed and structural_lines / max(len(changed), 1) >= 0.7:
        risks.append('测试改动多数是注解、导入、注释或空白调整，需人工确认不是噪声。')

    if non_test_patch_paths:
        risks.append('test.patch 包含非测试/fixture 文件，建议拆回 gold.patch: ' + ', '.join(non_test_patch_paths[:8]) + (' ...' if len(non_test_patch_paths) > 8 else '。'))

    if skip_lines > 0:
        risks.append(f'检测到约 {skip_lines} 行 skip/兼容性注解相关改动，需确认不是单纯放宽测试。')

    external_signals = sorted(
        label
        for label, pattern in EXTERNAL_TEST_PATCH_PATTERNS
        if re.search(pattern, changed_text, flags=re.I)
    )
    if external_signals:
        risks.append('test.patch 可能依赖本地 Docker 不保证存在的外部资源: ' + ', '.join(external_signals) + '。')

    added_versions = sorted(set(re.findall(r'version[^"\']*["\']([^"\']+)["\']', changed_text, flags=re.I)))
    if added_versions:
        risks.append('test.patch 修改了版本/发布相关断言，建议复核其是否属于用户可见问题修复。')

    if len(test_files) >= 3:
        strengths.append('测试改动覆盖多个测试文件，通常比单点 helper 锁定更抗过拟合。')

    force_high = bool(external_signals or non_test_patch_paths)

    if not risks:
        level = 'low'
        summary = 'test.patch 启发式检查未发现明显噪声信号。'
    elif force_high or len(risks) > 1:
        level = 'high'
        summary = 'test.patch 存在多项噪声风险，建议优先人工复核。'
    else:
        level = 'medium'
        summary = 'test.patch 存在一项噪声风险，建议在正式评测前复核。'

    return {
        'level': level,
        'summary': summary,
        'risks': risks,
        'strengths': strengths,
        'metrics': {
            'test_files': len(test_files),
            'diff_files': file_headers,
            'hunks': hunks,
            'changed_lines': len(changed),
            'assertion_lines': assertion_lines,
            'skip_lines': skip_lines,
            'structural_lines': structural_lines,
            'version_literals': added_versions,
            'external_resource_signals': external_signals,
            'non_test_patch_paths': non_test_patch_paths,
        },
        'requires_manual_review': level == 'high' or not changed,
    }


def assess_oracle_alignment(row: dict, test_patch: str, assessment: dict) -> dict:
    issue_text = '\n'.join(str(row.get(key) or '') for key in ('problem_statement', 'hints_text', 'title'))
    issue_lower = issue_text.lower()
    version_literals = ((assessment.get('metrics') or {}).get('version_literals') or [])
    risks: list[str] = []

    if version_literals and not any(token in issue_lower for token in ('version', 'release', 'bump', 'changelog')):
        risks.append('test.patch appears version/release oriented while the problem statement does not mention version or release behavior.')

    return {
        'ok': True,
        'blocking_reasons': [],
        'risks': risks,
    }


def env_bool(name: str) -> bool:
    return str(os.environ.get(name) or '').strip().lower() in {'1', 'true', 'yes', 'on'}


def truncate_text(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    head = max_chars // 2
    tail = max_chars - head
    return text[:head] + '\n\n[... truncated for oracle review ...]\n\n' + text[-tail:]


def extract_json_object(text: str) -> dict:
    cleaned = text.strip()
    if cleaned.startswith('```'):
        cleaned = re.sub(r'^```(?:json)?\s*', '', cleaned)
        cleaned = re.sub(r'\s*```$', '', cleaned)
    try:
        parsed = json.loads(cleaned)
    except json.JSONDecodeError:
        start = cleaned.find('{')
        end = cleaned.rfind('}')
        if start < 0 or end <= start:
            raise
        parsed = json.loads(cleaned[start:end + 1])
    if not isinstance(parsed, dict):
        raise ValueError('oracle review response is not a JSON object')
    return parsed


def build_oracle_review_prompt(
    row: dict,
    test_files: list[str],
    source_files: list[str],
    gold_patch: str,
    test_patch: str,
) -> str:
    issue_text = (row.get('problem_statement') or '').strip()
    return f'''You are reviewing a SWE-bench style task oracle for a multi-language benchmark.

Goal:
- Decide whether test.patch is a fair hidden oracle for the problem statement.
- A fair oracle tests the user-visible/public behavior, not the reference implementation.
- gold.patch is only evidence of one valid fix. Do not require candidates to match it.

Return JSON only, with this schema:
{{
  "verdict": "accept" | "rewrite" | "reject" | "needs_manual_review",
  "confidence": 0.0,
  "behavior_contract": "One or two sentences describing the minimal behavior test.patch should verify.",
  "fairness_summary": "Short explanation.",
  "gold_leakage_risks": [
    {{
      "evidence": "Specific test assertion/import/symbol/path that depends on gold implementation.",
      "why_overconstrained": "Why another valid implementation could fail it.",
      "rewrite_guidance": "How to test the behavior instead."
    }}
  ],
  "missing_oracle_risks": [
    {{
      "behavior": "Issue behavior not covered by test.patch.",
      "suggested_assertion": "A black-box assertion that would cover it."
    }}
  ],
  "stability_risks": [
    {{
      "evidence": "Network/time/randomness/concurrency/environment dependency, if any.",
      "fix": "How to make it deterministic."
    }}
  ],
  "rewrite_plan": [
    "Concrete, language-agnostic or project-specific steps to make test.patch minimal and fair."
  ]
}}

Decision rules:
- accept: test.patch is minimal, behavior-focused, and should accept alternative valid fixes.
- rewrite: test.patch is useful but includes over-specific implementation assertions.
- reject: test.patch is mostly unrelated, untestable, or requires hidden requirements absent from the issue.
- needs_manual_review: evidence is ambiguous or the patch is too large/truncated to judge.

Problem statement:
{truncate_text(issue_text, 12000)}

Changed source files in gold.patch:
{json.dumps(source_files[:80], ensure_ascii=False)}

Changed test files in test.patch:
{json.dumps(test_files[:80], ensure_ascii=False)}

gold.patch:
```diff
{truncate_text(gold_patch, 28000)}
```

test.patch:
```diff
{truncate_text(test_patch, 28000)}
```
'''


def call_openai_compatible_chat(
    base_url: str,
    api_key: str,
    model: str,
    prompt: str,
    timeout_seconds: int,
) -> str:
    url = base_url.rstrip('/') + '/chat/completions'
    payload = {
        'model': model,
        'temperature': 0,
        'messages': [
            {
                'role': 'system',
                'content': 'You are a rigorous benchmark-oracle reviewer. Return only valid JSON.',
            },
            {'role': 'user', 'content': prompt},
        ],
        'response_format': {'type': 'json_object'},
    }
    body = json.dumps(payload).encode('utf-8')
    req = urlrequest.Request(
        url,
        data=body,
        headers={
            'Authorization': f'Bearer {api_key}',
            'Content-Type': 'application/json',
        },
        method='POST',
    )
    with urlrequest.urlopen(req, timeout=timeout_seconds) as resp:
        data = json.loads(resp.read().decode('utf-8'))
    return data['choices'][0]['message']['content']


def run_llm_oracle_review(
    pkg: Path,
    row: dict,
    test_files: list[str],
    source_files: list[str],
    args: argparse.Namespace,
) -> dict:
    enabled = bool(getattr(args, 'llm_oracle_review', False) or env_bool('SWE_LLM_ORACLE_REVIEW'))
    model = getattr(args, 'llm_oracle_review_model', '') or os.environ.get('SWE_LLM_ORACLE_REVIEW_MODEL', '')
    if not enabled:
        return {'enabled': False, 'status': 'disabled'}
    if not model:
        return {'enabled': True, 'status': 'skipped', 'error': 'missing model; set --llm-oracle-review-model or SWE_LLM_ORACLE_REVIEW_MODEL'}
    api_key_env = getattr(args, 'llm_oracle_review_api_key_env', '') or os.environ.get('SWE_LLM_ORACLE_REVIEW_API_KEY_ENV', 'OPENAI_API_KEY')
    api_key = os.environ.get(api_key_env, '')
    if not api_key:
        return {'enabled': True, 'status': 'skipped', 'model': model, 'error': f'missing API key env {api_key_env}'}

    base_url = getattr(args, 'llm_oracle_review_base_url', '') or os.environ.get('SWE_LLM_ORACLE_REVIEW_BASE_URL', 'https://api.openai.com/v1')
    timeout_seconds = int(getattr(args, 'llm_oracle_review_timeout', 120) or os.environ.get('SWE_LLM_ORACLE_REVIEW_TIMEOUT', '120'))
    gold_patch = (pkg / 'patches/gold.patch').read_text(encoding='utf-8') if (pkg / 'patches/gold.patch').is_file() else ''
    test_patch = (pkg / 'patches/test.patch').read_text(encoding='utf-8') if (pkg / 'patches/test.patch').is_file() else ''
    prompt = build_oracle_review_prompt(row, test_files, source_files, gold_patch, test_patch)

    try:
        content = call_openai_compatible_chat(base_url, api_key, model, prompt, timeout_seconds)
        parsed = extract_json_object(content)
        parsed['enabled'] = True
        parsed['status'] = 'ok'
        parsed['model'] = model
        return parsed
    except (urlerror.URLError, urlerror.HTTPError, TimeoutError, KeyError, json.JSONDecodeError, ValueError) as exc:
        return {'enabled': True, 'status': 'error', 'model': model, 'error': str(exc)}


def merge_llm_oracle_review(assessment: dict, review: dict) -> dict:
    assessment = dict(assessment)
    risks = list(assessment.get('risks') or [])
    strengths = list(assessment.get('strengths') or [])
    metrics = dict(assessment.get('metrics') or {})
    metrics['llm_oracle_review_status'] = review.get('status')
    assessment['llm_oracle_review'] = review
    assessment['metrics'] = metrics

    if review.get('status') != 'ok':
        if review.get('enabled') and review.get('status') != 'disabled':
            risks.append('GPT oracle review 未完成: ' + str(review.get('error') or review.get('status')))
            assessment['requires_manual_review'] = True
            assessment['level'] = 'high'
        assessment['risks'] = risks
        assessment['strengths'] = strengths
        return assessment

    verdict = str(review.get('verdict') or '').lower()
    if verdict == 'accept':
        strengths.append('GPT oracle review 判定 test.patch 基本行为聚焦。')
    elif verdict in {'rewrite', 'reject', 'needs_manual_review'}:
        risks.append('GPT oracle review 判定为 ' + verdict + ': ' + str(review.get('fairness_summary') or '需复核隐藏测试是否过约束。'))
        assessment['requires_manual_review'] = True
        assessment['level'] = 'high'
    else:
        risks.append('GPT oracle review 返回未知 verdict，需人工复核。')
        assessment['requires_manual_review'] = True
        assessment['level'] = 'high'

    assessment['risks'] = risks
    assessment['strengths'] = strengths
    return assessment


def model_visible_problem_title(row: dict) -> str:
    issue_text = (row.get('problem_statement') or '').strip()
    for line in issue_text.splitlines():
        cleaned = line.strip().lstrip('#').strip()
        if cleaned:
            return cleaned[:120]
    return 'Fix the user-visible behavior described by the source issue'


def write_problem_statement(pkg: Path, row: dict, test_files: list[str], source_files: list[str]) -> None:
    issue_text = (row.get('problem_statement') or '').strip()
    title = model_visible_problem_title(row)
    text = f'''# {title}
'''
    if issue_text:
        text += f'''
## Issue Report

{issue_text}
'''
    else:
        text += '''
## Issue Report

The public issue text was not available in the candidate metadata. Reproduce and fix the user-visible behavior covered by this task without using upstream patch text.
'''
    (pkg/'problem_statement.md').write_text(text, encoding='utf-8')


def write_problem_stub(pkg: Path, row: dict) -> None:
    text = f'''# {PENDING_PROBLEM_STATEMENT}: {row.get('repo')}

## Background

待补充真实 bug / regression / PR review / user report 证据摘要。不要虚构要求。

## Expected Behavior

待补充测试实际验证的外部可观察行为。

## Constraints

- Preserve public API/interface unless the real PR changed it.
- Do not include hidden requirements not supported by the source evidence.
'''
    (pkg/'problem_statement.md').write_text(text, encoding='utf-8')


def write_evidence(pkg: Path, row: dict) -> None:
    text = f'''# Evidence

- Repo: https://github.com/{row.get('repo')}
- PR: {row.get('pr_url')}
- Issue: {row.get('issue_url') or '未提供'}
- Base commit: `{row.get('base_commit')}`
- Fix commit: `{row.get('fix_commit')}`
- Merge commit: `{row.get('merge_commit') or '未提供'}`

## Candidate Notes

- Score: {row.get('score')}
- Patch files: {row.get('patch_files')}
- Source files: {row.get('source_files')}
- Insertions: {row.get('insertions')}
- Deletions: {row.get('deletions')}
- Notes: {row.get('notes')}
'''
    (pkg/'evidence.md').write_text(text, encoding='utf-8')


def write_verification_summary(pkg: Path, row: dict, test_files: list[str], source_files: list[str], assessment: dict) -> None:
    path = pkg / 'verification.md'
    text = f'''# Verification

## Patch Application

- `bash scripts/verify_patch_application.sh`
- 状态：待仓库 checkout/patch 应用验证后补充。

## Baseline

- 命令：`bash scripts/run_selected_tests.sh baseline`
- 预期：失败（只应用 `test.patch`）。

## Fixed

- 命令：`bash scripts/run_selected_tests.sh fixed`
- 预期：通过（应用 `gold.patch` + `test.patch`）。

## Pass-To-Pass

- 命令：`bash scripts/run_selected_tests.sh pass-to-pass`
- 预期：通过（只应用 `gold.patch` 跑保留测试）。

## Test Patch Denoise Assessment

- Summary: {assessment['summary']}
- Level: {assessment['level']}
- Metrics: {json.dumps(assessment['metrics'], ensure_ascii=False)}
- Strengths:
{chr(10).join(f"  - {item}" for item in assessment['strengths']) if assessment['strengths'] else '  - 无明显增强信号'}
- Risks:
{chr(10).join(f"  - {item}" for item in assessment['risks']) if assessment['risks'] else '  - 未发现明显噪声风险'}

## Source Diff Scope

### Test Files

{summarize_test_files(test_files)}

### Production Files

{summarize_source_files(source_files)}

## Model Evaluation

- Opus 4.7 pass@8: {PENDING_MODEL_EVAL}
- Qwen3.6-Flash pass@4: {PENDING_MODEL_EVAL}

## Docker

- image tar: `docker-image/{pkg.name}-image.tar`
- sha256: {PENDING_DOCKER_EVIDENCE}
'''
    path.write_text(text, encoding='utf-8')


def write_acceptance_report(pkg: Path, row: dict, assessment: dict) -> None:
    path = pkg / '验收标准逐项对照报告.md'
    gold_files = row.get('gold_patch_files') or '0'
    gold_lines = row.get('gold_total_changed') or '0'
    summary = (
        f"真实仓库 `{row.get('repo')}`，来源 PR `{row.get('pr_url')}`。"
        f" 当前已自动生成 gold/test patch，gold 规模约 {gold_files} 文件 / {gold_lines} 行。"
        f" test.patch 去噪评估等级：{assessment['level']}。"
        f" Opus/Qwen 评测、Docker 证据和三审结果待后续阶段补充。"
    )
    text = f'''# {pkg.name} 验收标准逐项对照报告

对照文件：`swe-pro data 验收标准.docx`

## 总结

{summary}

## 逐项对照

| 序号 | 验收标准 | 样本状态 | 证据 / 说明 |
|---:|---|---|---|
| 1 | 环境可以跑通测试，并提供具体 docker image | 待验证 | Docker/image 证据在打包阶段补充；当前已生成运行脚本与 Dockerfile。 |
| 2 | golden patch 能通过测试，并与 issue/problem statement 相符 | 待验证 | 当前已生成 `gold.patch`、`test.patch`、`problem_statement.md`，待执行 baseline/fixed/pass-to-pass 验证。 |
| 3 | 基于真实软件工程 repo 及上下文 | 已满足 | Repo=`{row.get('repo')}`，PR=`{row.get('pr_url')}`。 |
| 4 | 真实 issue + 修复 commit；禁止虚构 | 部分满足 | Base=`{row.get('base_commit')}`，Fix=`{row.get('fix_commit')}`；Issue 链接：{row.get('issue_url') or '未提供，需要后续补充证据说明'}。 |
| 9 | ground-truth patch 多文件且几百行以上 | 已满足 | 当前候选统计：gold 约 {gold_files} 文件 / {gold_lines} 行。 |
| 10 | opus4.7 pass@8 != 0 | 待评测 | {PENDING_MODEL_EVAL} |
| 11 | qwen 3.6 flash pass rate@4 <= 50% | 待评测 | {PENDING_MODEL_EVAL} |
| 12 | repo 语言尽可能覆盖多个语言 | 批量项 | {PENDING_BATCH_EVIDENCE} |
| 16 | test patch 避免 narrow/overfit | {"需复核" if assessment['requires_manual_review'] else "初步通过"} | {assessment['summary']} {'; '.join(assessment['risks']) if assessment['risks'] else '未发现明显噪声风险'} |
| 18 | issue_specificity / issue_categories 分布 | 批量项 | {PENDING_BATCH_EVIDENCE} |
| 19 | 三重盲审 + 评审校准 | 待审校 | {PENDING_REVIEW} |
'''
    path.write_text(text, encoding='utf-8')


def write_review_placeholders(pkg: Path, row: dict, assessment: dict) -> None:
    review_dir = pkg / 'review'
    files = {
        'reviewer_1.md': f'''# Reviewer 1 Blind Review

Package: {pkg.name}
Focus: authenticity, problem statement, patch relevance.

## Verdict

{PENDING_REVIEW}: 完成真实性与问题陈述核对后填写。

## Checks

| Check | Result | Notes |
|---|---|---|
| Real repo / PR / commit | 待审校 | Repo=`{row.get('repo')}` PR=`{row.get('pr_url')}` |
| Problem matches patch | 待审校 | 需人工核对 `problem_statement.md` 与 `gold.patch` |
| No reverse-fabrication | 待审校 | 需对照 PR/issue 证据 |
| Gold patch size | 已生成 | 候选统计约 {row.get('gold_patch_files') or '0'} 文件 / {row.get('gold_total_changed') or '0'} 行 |
''',
        'reviewer_2.md': f'''# Reviewer 2 Blind Review

Package: {pkg.name}
Focus: tests, overfit risk, alternate-patch tolerance.

## Verdict

{PENDING_REVIEW}: 完成 baseline/fixed/pass-to-pass 与过拟合复核后填写。

## Checks

| Check | Result | Notes |
|---|---|---|
| Baseline fails | 待验证 | 运行 `bash scripts/run_selected_tests.sh baseline` |
| Fixed passes | 待验证 | 运行 `bash scripts/run_selected_tests.sh fixed` |
| Avoids helper-name lock | {"需复核" if assessment['requires_manual_review'] else "初步通过"} | {assessment['summary']} |
| Alternative valid patches can pass | 待审校 | 需人工检查测试约束是否过窄 |
''',
        'reviewer_3.md': f'''# Reviewer 3 Blind Review

Package: {pkg.name}
Focus: delivery completeness, Docker, metadata, model evidence.

## Verdict

{PENDING_REVIEW}: 完成 Docker、模型评测和交付清洁度核对后填写。

## Checks

| Check | Result | Notes |
|---|---|---|
| Docker evidence | 待补充 | {PENDING_DOCKER_EVIDENCE} |
| Model evidence | 待补充 | {PENDING_MODEL_EVAL} |
| Language/category evidence | 批量项 | {PENDING_BATCH_EVIDENCE} |
| Hygiene | 待验证 | 待运行交付清洁度检查 |
''',
        'adjudication_and_calibration.md': f'''# Adjudication And Reviewer Calibration

Package: {pkg.name}

## Calibration Rule

- Authenticity
- Relevance
- Difficulty
- Test quality
- Alternate solution tolerance
- Evidence completeness

## Final Decision

{PENDING_REVIEW}: 待三位 reviewer 完成后填写最终结论。
''',
    }
    for name, text in files.items():
        (review_dir / name).write_text(text, encoding='utf-8')


def update_readme_stub(pkg: Path, row: dict) -> None:
    path = pkg / 'README.md'
    if not path.exists():
        return
    text = path.read_text(encoding='utf-8')
    text = re.sub(r'Base commit: `[^`]*`', f"Base commit: `{row.get('base_commit')}`", text)
    text = re.sub(r'Fix commit: `[^`]*`', f"Fix commit: `{row.get('fix_commit')}`", text)
    path.write_text(text, encoding='utf-8')


def update_run_selected_tests(pkg: Path, fail_cmd: str, pass_cmd: str, before_cmd: str = '') -> None:
    path = pkg / 'scripts' / 'run_selected_tests.sh'
    if not path.exists() or not fail_cmd:
        return
    fail_cmd = normalize_python_test_command(fail_cmd)
    pass_cmd = normalize_python_test_command(pass_cmd)
    before_assignment = shlex.quote(before_cmd or '')
    python_path_export = 'export PYTHONPATH="$ROOT/repo:${PYTHONPATH:-}"\n' if is_python_test_command(fail_cmd, pass_cmd) else ''
    pass_block = f'''    git apply "$ROOT/patches/gold.patch"
    run_before_repo_set_cmd
    {pass_cmd}
''' if pass_cmd else '''    echo "pass-to-pass command is required but was not generated" >&2
    exit 2
'''
    text = f'''#!/usr/bin/env bash
set -euo pipefail
MODE="${{1:-baseline}}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/repo"
{python_path_export}if [ ! -d .git ]; then
  git init -q
  git config user.email "fly-agent@example.invalid"
  git config user.name "fly-agent"
  git add -A
  git commit -q -m "baseline snapshot"
fi
BEFORE_REPO_SET_CMD={before_assignment}
run_before_repo_set_cmd() {{
  if [ -n "$BEFORE_REPO_SET_CMD" ]; then
    eval "$BEFORE_REPO_SET_CMD"
  fi
}}
git reset --hard HEAD >/dev/null
git clean -fd >/dev/null
case "$MODE" in
  baseline)
    git apply "$ROOT/patches/test.patch"
    run_before_repo_set_cmd
    {fail_cmd}
    ;;
  fixed)
    git apply "$ROOT/patches/gold.patch"
    git apply "$ROOT/patches/test.patch"
    run_before_repo_set_cmd
    {fail_cmd}
    ;;
  pass-to-pass)
{pass_block.rstrip()}
    ;;
  *)
    echo "usage: run_selected_tests.sh [baseline|fixed|pass-to-pass]" >&2
    exit 2
    ;;
esac
'''
    path.write_text(text, encoding='utf-8')
    path.chmod(0o755)


def is_python_test_command(*commands: str) -> bool:
    text = '\n'.join(command or '' for command in commands)
    return bool(re.search(r'\bpython(?:3)?\s+-m\s+pytest\b|\bpytest\b', text))


def normalize_python_test_command(command: str) -> str:
    if not command:
        return command
    return re.sub(r'\bpython\s+-m\s+pytest\b', 'python3 -m pytest', command)


def reference_roots_from_env() -> list[Path]:
    raw = os.environ.get('SWE_REFERENCE_PACKAGES_ROOT', '')
    return [Path(part).expanduser().resolve() for part in raw.split(os.pathsep) if part.strip()]


def reference_matches(reference: Path, row: dict) -> bool:
    task_path = reference / 'task.json'
    if not task_path.is_file():
        return False
    try:
        task = json.loads(task_path.read_text(encoding='utf-8'))
    except Exception:
        return False
    pr_url = (row.get('pr_url') or '').strip()
    if not pr_url:
        return False
    metadata = task.get('metadata') or {}
    links = task.get('evidence_links') or []
    return (
        metadata.get('source_pr') == pr_url
        or pr_url in links
        or task.get('source_pr') == pr_url
    )


def find_reference_package(row: dict, roots: list[Path] | None = None) -> Path | None:
    search_roots = roots if roots is not None else reference_roots_from_env()
    if not search_roots:
        return None
    expected = f"production-task-{slug_repo(row.get('repo') or '')}-{pr_number(row.get('pr_url') or '')}"
    for root in search_roots:
        direct = root / expected
        if reference_matches(direct, row):
            return direct
    for root in search_roots:
        if not root.is_dir():
            continue
        for candidate in sorted(root.glob('production-task-*')):
            if reference_matches(candidate, row):
                return candidate
    return None


def copy_if_exists(src: Path, dst: Path) -> None:
    if not src.is_file():
        return
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)


def parse_json_list(value) -> list[str]:
    if isinstance(value, list):
        return [str(item).strip() for item in value if str(item).strip()]
    if not isinstance(value, str) or not value.strip():
        return []
    raw = value.strip()
    try:
        parsed = json.loads(raw)
    except Exception:
        parsed = None
    if isinstance(parsed, list):
        return [str(item).strip() for item in parsed if str(item).strip()]
    return [item.strip() for item in raw.split(',') if item.strip()]


def first_nonempty_list(*values) -> list[str]:
    for value in values:
        parsed = parse_json_list(value)
        if parsed:
            return parsed
    return []


def changed_paths_from_patch(patch: str) -> list[str]:
    paths: list[str] = []
    for line in patch.splitlines():
        if not line.startswith('diff --git a/'):
            continue
        parts = line.split()
        if len(parts) >= 4:
            paths.append(parts[2][2:])
    return paths


def added_lines_from_patch(patch: str) -> list[str]:
    return [
        line[1:]
        for line in patch.splitlines()
        if line.startswith('+') and not line.startswith('+++')
    ]


def removed_lines_from_patch(patch: str) -> list[str]:
    return [
        line[1:]
        for line in patch.splitlines()
        if line.startswith('-') and not line.startswith('---')
    ]


def changed_lines_from_patch(patch: str) -> list[str]:
    return added_lines_from_patch(patch) + removed_lines_from_patch(patch)


def is_test_support_file(path: str) -> bool:
    lower = path.lower()
    parts = lower.split('/')
    name = parts[-1]
    return (
        is_test_file(path)
        or any(part in {
            'testdata', 'fixtures', 'fixture', 'snapshots', '__snapshots__',
            'golden', 'goldens', 'mocks', 'mock', 'stubs', 'stub',
        } for part in parts)
        or '/src/test/resources/' in lower
        or '/src/androidtest/assets/' in lower
        or '/src/androidtest/res/' in lower
        or name.endswith(('.snap', '.snapshot', '.golden'))
    )


def infer_issue_tags(row: dict, data: dict, test_files: list[str] | None) -> tuple[list[str], list[str]]:
    metadata = data.get('metadata') if isinstance(data.get('metadata'), dict) else {}
    specificity = first_nonempty_list(
        row.get('issue_specificity'),
        data.get('issue_specificity'),
        metadata.get('issue_specificity') if metadata else None,
    )
    categories = first_nonempty_list(
        row.get('issue_categories'),
        data.get('issue_categories'),
        metadata.get('issue_categories') if metadata else None,
    )
    if specificity and categories:
        return specificity, categories

    lang = (row.get('primary_language') or data.get('repo_language') or '').lower()
    text = ' '.join(str(x or '') for x in [
        row.get('problem_statement'),
        row.get('hints_text'),
        data.get('problem_statement'),
        row.get('issue_url'),
        row.get('pr_url'),
    ]).lower()
    paths = changed_paths_from_patch(str(data.get('patch') or '')) + (test_files or [])
    path_text = ' '.join(paths).lower()
    signal = text + ' ' + path_text
    is_bug = any(word in signal for word in [
        'bug', 'fix', 'crash', 'fail', 'error', 'exception', 'broken', 'wrong',
        'regression', 'null', 'undefined', 'incorrect', 'timeout',
    ])
    suffix = 'bug' if is_bug else 'feat'

    if not specificity:
        inferred: list[str] = []
        frontend_signal = lang in {'javascript', 'typescript'} or any(
            token in path_text for token in ['src/pages/', 'components/', 'frontend/', 'ui/', '.jsx', '.tsx', '.css', '.scss']
        )
        if frontend_signal:
            inferred.append(f'ui_ux_{suffix}')
        if any(token in signal for token in ['custom', 'config', 'setting', 'option', 'preference', 'theme', 'layout']):
            inferred.append('customization_feat')
        if any(token in signal for token in ['api', 'endpoint', 'rest', 'graphql', 'schema', 'request', 'response']):
            inferred.append(f'api_{suffix}')
        if any(token in signal for token in ['integration', 'plugin', 'external', 'client', 'server', 'service', 'connector']):
            inferred.append(f'integration_{suffix}')
        specificity = list(dict.fromkeys(inferred)) or [f'api_{suffix}']

    if not categories:
        inferred = []
        if lang in {'javascript', 'typescript'} or any(token in path_text for token in ['frontend/', 'src/pages/', 'components/', '.jsx', '.tsx', '.css']):
            inferred.extend(['front_end_knowledge', 'web_knowledge'])
        if lang in {'java', 'go', 'python', 'rust'} or any(token in path_text for token in ['server/', 'backend/', 'src/main/', 'pkg/', 'cmd/']):
            inferred.append('back_end_knowledge')
        if any(token in signal for token in ['ui', 'ux', 'component', 'page', 'layout', 'style', 'css', 'theme']):
            inferred.append('ui_ux_knowledge')
        if any(token in signal for token in ['api', 'endpoint', 'rest', 'graphql', 'request', 'response']):
            inferred.append('api_knowledge')
        categories = list(dict.fromkeys(inferred)) or ['back_end_knowledge']

    return specificity, categories


def hydrate_from_reference_package(pkg: Path, reference: Path) -> dict:
    task = json.loads((reference / 'task.json').read_text(encoding='utf-8'))
    test_patch = reference / 'patches' / 'test.patch'
    if not test_patch.is_file() or not test_patch.read_text(encoding='utf-8').strip():
        raise RuntimeError(f'reference package has empty test.patch: {reference}')
    copy_if_exists(test_patch, pkg / 'patches' / 'test.patch')
    for name in ['reviewer_1.md', 'reviewer_2.md', 'reviewer_3.md', 'adjudication_and_calibration.md']:
        copy_if_exists(reference / 'review' / name, pkg / 'review' / name)

    fail_to_pass = task.get('fail_to_pass') if isinstance(task.get('fail_to_pass'), list) else []
    pass_to_pass = task.get('pass_to_pass') if isinstance(task.get('pass_to_pass'), list) else []
    test_files = task.get('selected_test_files_to_run') if isinstance(task.get('selected_test_files_to_run'), list) else []
    if not fail_to_pass or not pass_to_pass or not test_files:
        raise RuntimeError(f'reference package missing oracle commands or selected tests: {reference}')
    update_run_selected_tests(pkg, fail_to_pass[0], pass_to_pass[0], str(task.get('before_repo_set_cmd') or ''))

    verification = (task.get('metadata') or {}).get('verification') or {}
    assessment = verification.get('test_patch_denoise_assessment')
    if not isinstance(assessment, dict):
        assessment = assess_test_patch(
            (pkg / 'patches/test.patch').read_text(encoding='utf-8'),
            test_files,
            (pkg / 'patches/gold.patch').read_text(encoding='utf-8') if (pkg / 'patches/gold.patch').is_file() else '',
            str(task.get('problem_statement') or ''),
        )
    return {
        'test_files': test_files,
        'fail_to_pass': fail_to_pass,
        'pass_to_pass': pass_to_pass,
        'interface': task.get('interface') or 'Imported from reference package oracle.',
        'requirements': task.get('requirements'),
        'before_repo_set_cmd': task.get('before_repo_set_cmd'),
        'issue_specificity': first_nonempty_list(
            task.get('issue_specificity'),
            (task.get('metadata') or {}).get('issue_specificity') if isinstance(task.get('metadata'), dict) else None,
        ),
        'issue_categories': first_nonempty_list(
            task.get('issue_categories'),
            (task.get('metadata') or {}).get('issue_categories') if isinstance(task.get('metadata'), dict) else None,
        ),
        'assessment': assessment,
    }


def update_task_json_stub(
    pkg: Path,
    row: dict,
    test_files: list[str] | None = None,
    assessment: dict | None = None,
    oracle: dict | None = None,
    test_ids: list[str] | None = None,
) -> None:
    path = pkg/'task.json'
    try:
        data=json.loads(path.read_text(encoding='utf-8'))
    except Exception:
        return
    ps=(pkg/'problem_statement.md').read_text(encoding='utf-8')
    gold=(pkg/'patches/gold.patch').read_text(encoding='utf-8') if (pkg/'patches/gold.patch').exists() else ''
    test=(pkg/'patches/test.patch').read_text(encoding='utf-8') if (pkg/'patches/test.patch').exists() else ''
    data['problem_statement']=ps
    data['patch']=gold
    data['test_patch']=test
    data['repo']=row.get('repo') or data.get('repo')
    repo_url = f"https://github.com/{row.get('repo')}" if row.get('repo') else data.get('repo_url')
    source_url = row.get('pr_url') or data.get('source_url')
    if repo_url:
        data['repo_url'] = repo_url
    if source_url:
        data['source_url'] = source_url
    data['base_commit']=row.get('base_commit') or data.get('base_commit')
    data['repo_language']=row.get('primary_language') or data.get('repo_language')
    oracle_specificity = oracle.get('issue_specificity') if oracle else None
    oracle_categories = oracle.get('issue_categories') if oracle else None
    issue_specificity, issue_categories = (
        first_nonempty_list(oracle_specificity),
        first_nonempty_list(oracle_categories),
    )
    if not issue_specificity or not issue_categories:
        inferred_specificity, inferred_categories = infer_issue_tags(row, data, test_files)
        issue_specificity = issue_specificity or inferred_specificity
        issue_categories = issue_categories or inferred_categories
    data['issue_specificity'] = issue_specificity
    data['issue_categories'] = issue_categories
    if test_files is not None:
        test_language = language_from_selected_tests(test_files)
        if test_language:
            data['repo_language'] = test_language
        fail_cmd = oracle['fail_to_pass'][0] if oracle else test_command_for_files(pkg, row, test_files, test_ids)
        pass_cmd = oracle['pass_to_pass'][0] if oracle else pass_to_pass_command_for_files(pkg, row, test_files, test_ids)
        data['fail_to_pass'] = [fail_cmd] if test_files else []
        data['pass_to_pass'] = [pass_cmd] if pass_cmd and test_files else []
        data['selected_test_files_to_run'] = test_files
        if test_ids:
            data['selected_test_ids_to_run'] = test_ids
        if oracle:
            data['interface'] = oracle['interface']
        else:
            data['interface'] = derive_interface_text() if test_files else 'No source PR test files were detected; add tests before evaluation.'
        if oracle and oracle.get('requirements') is not None:
            data['requirements'] = oracle.get('requirements')
        elif test_files:
            data['requirements'] = derive_requirements_text(row)
        before_cmd = oracle.get('before_repo_set_cmd') if oracle else data.get('before_repo_set_cmd')
        if before_cmd:
            data['before_repo_set_cmd'] = before_cmd
        update_run_selected_tests(pkg, fail_cmd, pass_cmd, before_cmd or '')
    data['evidence_links']=[x for x in [repo_url, source_url, row.get('issue_url'), f"https://github.com/{row.get('repo')}/commit/{row.get('base_commit')}", f"https://github.com/{row.get('repo')}/commit/{row.get('fix_commit')}"] if x]
    md=data.setdefault('metadata', {})
    md['source_pr']=row.get('pr_url')
    md['base_commit_url']=f"https://github.com/{row.get('repo')}/commit/{row.get('base_commit')}"
    md['fix_commit_url']=f"https://github.com/{row.get('repo')}/commit/{row.get('fix_commit')}"
    md['repo_language'] = data.get('repo_language')
    md['issue_specificity'] = issue_specificity
    md['issue_categories'] = issue_categories
    candidate_pr_patch_stats = {
        'files': row.get('patch_files'),
        'source_files': row.get('source_files'),
        'insertions': row.get('insertions'),
        'deletions': row.get('deletions'),
        'total_changed': row.get('total_changed'),
        'gold_patch_files': row.get('gold_patch_files'),
        'gold_source_files': row.get('gold_source_files'),
        'gold_insertions': row.get('gold_insertions'),
        'gold_deletions': row.get('gold_deletions'),
        'gold_total_changed': row.get('gold_total_changed'),
        'test_patch_files': row.get('test_patch_files'),
        'test_insertions': row.get('test_insertions'),
        'test_deletions': row.get('test_deletions'),
        'test_total_changed': row.get('test_total_changed'),
    }
    actual_patch_stats = combined_patch_stats(gold, test)
    md['candidate_pr_patch_stats'] = candidate_pr_patch_stats
    md['patch_stats'] = actual_patch_stats
    if assessment is not None:
        verification = md.setdefault('verification', {})
        verification['test_patch_denoise_assessment'] = assessment
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')


def init_package(row: dict, out_root: Path, dry_run: bool) -> Path:
    repo=row['repo']; pr=pr_number(row.get('pr_url',''))
    pkg_name=f"production-task-{slug_repo(repo)}-{pr}"
    pkg=out_root/pkg_name
    lang=(row.get('primary_language') or 'go').lower()
    defaults=LANG_DEFAULTS.get(lang, LANG_DEFAULTS['go'])
    cmd=[sys.executable, str(INIT_SCRIPT), '--package-name', pkg_name, '--repo', repo, '--source-pr', row.get('pr_url') or f'https://github.com/{repo}/pull/{pr}', '--base-commit', row.get('base_commit'), '--fix-commit', row.get('fix_commit'), '--language', lang, '--before-cmd', defaults['before_cmd'], '--base-image', defaults['base_image'], '--language-dependencies-cmd', defaults['dependencies_cmd'], '--toolchain-stages', defaults.get('toolchain_stages', ''), '--toolchain-copy', defaults.get('toolchain_copy', ''), '--fail-cmd', defaults['fail_cmd'], '--pass-cmd', defaults['pass_cmd'], '--out-root', str(out_root)]
    if dry_run:
        print('DRY init:', ' '.join(cmd))
        return pkg
    if not pkg.exists():
        run(cmd)
    refresh_dockerfile(pkg, lang)
    return pkg


def refresh_dockerfile(pkg: Path, lang: str) -> None:
    if lang not in {'javascript', 'typescript'}:
        return
    dockerfile = pkg / 'dockerfiles' / 'Dockerfile'
    if not dockerfile.is_file():
        return
    text = dockerfile.read_text(encoding='utf-8')
    normalized = text
    normalized = normalized.replace(NODE_TOOLCHAIN_STAGE + '\n', '')
    normalized = normalized.replace(NODE_TOOLCHAIN_COPY + '\n', '')
    normalized = normalized.replace('\n' + NODE_TOOLCHAIN_COPY, '')
    normalized = normalized.replace('RUN ' + OLD_NODE_DEPENDENCIES_CMD + '\n', '')
    normalized = normalized.replace('FROM node:22-bookworm\n', 'FROM ubuntu:22.04\n')
    normalized = normalized.replace('FROM ubuntu:22.04\nFROM ubuntu:22.04', 'FROM ubuntu:22.04')
    normalized = re.sub(
        r'(?m)^RUN .*apt-get update.*(?:deb\.nodesource\.com|nodejs).*?$',
        'RUN ' + NODE_DEPENDENCIES_CMD,
        normalized,
    )
    if NODE_DEPENDENCIES_CMD not in normalized:
        normalized = normalized.replace('WORKDIR /workspace\n', 'WORKDIR /workspace\n\nRUN ' + NODE_DEPENDENCIES_CMD + '\n', 1)
    install_dir = docker_npm_install_dir(pkg)
    normalized = normalized.replace('&& cd /workspace/repo/web \\\n    && npm install', f'&& cd {install_dir} \\\n    && npm install')
    normalized = normalized.replace('&& cd /workspace/repo \\\n    && npm install', f'&& cd {install_dir} \\\n    && npm install')
    if normalized != text:
        dockerfile.write_text(normalized, encoding='utf-8')


def main() -> int:
    ap=argparse.ArgumentParser(description='Batch initialize task packages from discovered candidate CSV.')
    ap.add_argument('--candidates', required=True)
    ap.add_argument('--out-root', default='.')
    ap.add_argument('--min-score', type=int, default=70)
    ap.add_argument('--statuses', default='scored,selected', help='comma-separated candidate_status values to include; empty means all')
    ap.add_argument('--limit', type=int, default=0)
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--no-clone', action='store_true', help='Only scaffold package dirs; do not clone repo or generate gold.patch')
    ap.add_argument('--continue-on-error', action='store_true', help='Continue preparing later candidates when one candidate fails')
    ap.add_argument('--only', action='append', default=[], help='Only prepare rows matching this candidate_id or pr_url; repeatable')
    ap.add_argument('--llm-oracle-review', action='store_true', help='Use an OpenAI-compatible GPT reviewer to assess test.patch fairness and rewrite risk')
    ap.add_argument('--llm-oracle-review-model', default=os.environ.get('SWE_LLM_ORACLE_REVIEW_MODEL', ''))
    ap.add_argument('--llm-oracle-review-base-url', default=os.environ.get('SWE_LLM_ORACLE_REVIEW_BASE_URL', 'https://api.openai.com/v1'))
    ap.add_argument('--llm-oracle-review-api-key-env', default=os.environ.get('SWE_LLM_ORACLE_REVIEW_API_KEY_ENV', 'OPENAI_API_KEY'))
    ap.add_argument('--llm-oracle-review-timeout', type=int, default=int(os.environ.get('SWE_LLM_ORACLE_REVIEW_TIMEOUT', '120')))
    args=ap.parse_args()
    rows=read_candidates(Path(args.candidates))
    statuses={x.strip() for x in args.statuses.split(',') if x.strip()}
    only={x.strip() for x in args.only if x.strip()}
    chosen=filter_selected_rows(rows, only, args.min_score, statuses)
    if args.limit:
        chosen=chosen[:args.limit]
    out_root=Path(args.out_root).resolve()
    print(f'selected {len(chosen)} candidates')
    failures: list[tuple[dict, Exception]] = []
    for row in chosen:
        try:
            print(f"preparing {row.get('repo')} {row.get('pr_url')}", flush=True)
            pkg=init_package(row, out_root, args.dry_run)
            if args.dry_run:
                continue
            test_files: list[str] | None = None
            source_files: list[str] = []
            assessment: dict | None = None
            oracle: dict | None = None
            test_ids: list[str] | None = None
            if not args.no_clone:
                resolved_base = ensure_repo(pkg, row['repo'], row.get('pr_url',''), row['base_commit'], row['fix_commit'])
                row = {**row, 'base_commit': resolved_base}
                test_files, source_files = generate_patches(pkg, row['base_commit'], row['fix_commit'])
                test_patch_text = (pkg/'patches/test.patch').read_text(encoding='utf-8')
                test_ids = extract_test_ids_from_patch(test_patch_text)
                assessment = assess_test_patch(
                    test_patch_text,
                    test_files,
                    (pkg / 'patches/gold.patch').read_text(encoding='utf-8') if (pkg / 'patches/gold.patch').is_file() else '',
                    row.get('problem_statement') or '',
                )
                if test_patch_text.strip() == '':
                    reference = find_reference_package(row)
                    if reference is not None:
                        print(f'importing oracle from reference package {reference}', flush=True)
                        oracle = hydrate_from_reference_package(pkg, reference)
                        test_files = oracle['test_files']
                        assessment = oracle['assessment']
                        test_ids = None
                    else:
                        raise RuntimeError(
                            'candidate rejected: no real test.patch found in PR diff and no reference oracle package is available; '
                            'LLM-generated gold/test patch fallback is disabled'
                        )
            if test_files is not None:
                if assessment is not None:
                    review = run_llm_oracle_review(pkg, row, test_files, source_files, args)
                    assessment = merge_llm_oracle_review(assessment, review)
                write_problem_statement(pkg, row, test_files, source_files)
            else:
                write_problem_stub(pkg, row)
            write_evidence(pkg, row)
            if assessment is not None and test_files is not None:
                write_verification_summary(pkg, row, test_files, source_files, assessment)
                write_acceptance_report(pkg, row, assessment)
                if oracle is None:
                    write_review_placeholders(pkg, row, assessment)
            update_readme_stub(pkg, row)
            update_task_json_stub(pkg, row, test_files, assessment, oracle, test_ids)
            print(f'prepared {pkg}')
        except Exception as exc:
            if not args.continue_on_error:
                raise
            failures.append((row, exc))
            print(f"failed {row.get('repo')} {row.get('pr_url')}: {exc}", file=sys.stderr)
    if failures:
        print(f'failed {len(failures)} candidates', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
