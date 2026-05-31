#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, os, re, subprocess, time, urllib.request, urllib.error
from pathlib import Path

ANTHROPIC_CALL_MODES = {'auto', 'sdk', 'cli'}

STANDARD_EVALUATION_STATUSES = {
    'resolved',
    'partial',
    'compile_error',
    'patch_apply_failed',
    'test_infra_failed',
    'invalid',
}


def log(message: str) -> None:
    print(f"[eval {time.strftime('%Y-%m-%d %H:%M:%S')}] {message}", flush=True)


def model_eval_metadata_key(model: str) -> str:
    lower = model.lower()
    if 'opus' in lower:
        return 'opus4_7_pass_at_8'
    if 'qwen' in lower:
        return 'qwen3_6_plus_pass_at_4'
    safe = re.sub(r'[^a-z0-9]+', '_', lower).strip('_')
    return f'{safe}_evaluation'


def model_eval_metadata_value(summary: dict) -> str:
    passes = int(summary.get('passes') or 0)
    attempts = int(summary.get('attempts') or 0)
    pass_rate = float(summary.get('pass_rate') or 0)
    model = str(summary.get('model') or '')
    output_dir = str(summary.get('output_dir') or '')
    if 'qwen' in model.lower():
        target = '满足要求' if pass_rate <= 0.5 else '不满足要求'
        requirement = 'Qwen pass rate@4 <= 50%'
    elif 'opus' in model.lower():
        target = '满足要求' if passes > 0 else '不满足要求'
        requirement = 'Opus pass@8 != 0'
    else:
        target = '已完成'
        requirement = 'model evaluation completed'
    return f'{passes}/{attempts} ({pass_rate:.0%})，{target}：{requirement}；证据目录：{output_dir}'


def sync_task_model_evaluation(pkg: Path, summary: dict) -> None:
    task_path = pkg / 'task.json'
    try:
        task = json.loads(task_path.read_text(encoding='utf-8'))
    except Exception as e:
        log(f"task.json metadata sync skipped error={e}")
        return
    metadata = task.setdefault('metadata', {})
    model_eval = metadata.setdefault('model_evaluation', {})
    key = model_eval_metadata_key(str(summary.get('model') or ''))
    model_eval[key] = model_eval_metadata_value(summary)
    task_path.write_text(json.dumps(task, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    log(f"task.json model_evaluation synced key={key} value={model_eval[key]}")


def run(cmd: list[str], cwd: Path) -> tuple[int, str]:
    start = time.monotonic()
    log(f"command start cwd={cwd} cmd={' '.join(cmd)}")
    p=subprocess.run(cmd, cwd=cwd, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    elapsed = time.monotonic() - start
    output = p.stdout or ''
    log(f"command done exit_code={p.returncode} elapsed={elapsed:.2f}s output_bytes={len(output.encode('utf-8'))}")
    if output.strip():
        log("command output begin")
        for line in output.rstrip('\n').splitlines():
            print(f"[cmd] {line}", flush=True)
        log("command output end")
    return p.returncode, p.stdout


def is_infrastructure_failure(output: str) -> bool:
    lower = (output or '').lower()
    needles = [
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
        'cc: command not found',
        'c++: command not found',
        'cmake: command not found',
        'make: command not found',
        'php: command not found',
        'composer: command not found',
        'swift: command not found',
        'kotlinc: command not found',
        'kotlin: command not found',
        'xcrun: error: invalid active developer path',
        'missing xcrun',
        'no such file or directory: \'go\'',
        'no such file or directory: "go"',
        'could not resolve dependencies for project',
        'failed to collect dependencies',
        'npm err! code eai_again',
        'npm err! code enotfound',
        'npm err! code econnreset',
        'temporary failure in name resolution',
        'connection timed out',
        'proxyconnect tcp',
    ]
    return any(needle in lower for needle in needles)


def is_compile_failure(output: str) -> bool:
    lower = (output or '').lower()
    needles = [
        'compile error',
        'compilation failed',
        'syntaxerror',
        'typeerror:',
        'cannot find symbol',
        'undefined:',
        'build failed',
        'failed to compile',
        'error: could not compile',
    ]
    return any(needle in lower for needle in needles)


def standard_status(result: dict, log_text: str = '') -> str:
    if result.get('passed'):
        return 'resolved'
    error = str(result.get('error') or '')
    signal = (error + '\n' + (log_text or '')).lower()
    if is_infrastructure_failure(signal):
        return 'test_infra_failed'
    if 'did not apply' in signal or ('git apply' in signal and 'error:' in signal):
        return 'patch_apply_failed'
    if is_compile_failure(signal):
        return 'compile_error'
    if result.get('model_patch_applied') and (
        result.get('fail_to_pass_passed') or result.get('pass_to_pass_passed')
    ):
        return 'partial'
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


def safe_task_metadata(pkg: Path) -> dict:
    task = json.loads((pkg / 'task.json').read_text(encoding='utf-8'))
    metadata = task.get('metadata') if isinstance(task.get('metadata'), dict) else {}
    evidence_links = task.get('evidence_links') if isinstance(task.get('evidence_links'), list) else []
    return {
        'instance_id': task.get('instance_id') or task.get('task_id') or pkg.name,
        'repo': task.get('repo'),
        'source_pr': metadata.get('source_pr') or task.get('source_pr'),
        'issue_url': next((link for link in evidence_links if isinstance(link, str) and '/issues/' in link), ''),
        'base_commit': task.get('base_commit'),
    }


def write_evaluation_artifacts(pkg: Path, run_dir: Path, result: dict, model: str) -> None:
    eval_log = run_dir / 'eval.log'
    log_text = eval_log.read_text(encoding='utf-8', errors='replace') if eval_log.is_file() else ''
    status = standard_status(result, log_text)
    result['status'] = status
    test_output = test_output_for_result(result, status)
    (run_dir / 'test_output.json').write_text(json.dumps(test_output, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    metadata = safe_task_metadata(pkg)
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


def first_cmd(task: dict, key: str) -> str:
    value = task.get(key) or []
    return value[0] if isinstance(value, list) and value else ''


def reset_repo(pkg: Path) -> None:
    log(f"reset repo start repo={pkg/'repo'}")
    run(['git','-C',str(pkg/'repo'),'reset','--hard','HEAD'], pkg)
    run(['git','-C',str(pkg/'repo'),'clean','-fd'], pkg)
    log("reset repo done")


def extract_json(text: str) -> dict:
    parsed = []
    errors = []
    for candidate in json_response_candidates(text):
        try:
            value = parse_json_response_candidate(candidate)
        except Exception as e:
            errors.append(str(e))
            continue
        if isinstance(value, dict):
            parsed.append(value)
            if isinstance(value.get('edits'), list) or isinstance(value.get('patch'), str):
                return value
    if parsed:
        return parsed[0]
    if errors:
        raise ValueError('no valid JSON object found: ' + '; '.join(errors[:3]))
    raise ValueError('no JSON object found')


def json_response_candidates(text: str) -> list[str]:
    candidates = []
    for match in re.finditer(r'```(?:json)?\s*\n(.*?)\n```', text, flags=re.S | re.I):
        candidates.append(match.group(1).strip())
    candidates.extend(balanced_json_object_candidates(text))
    stripped = text.strip()
    if stripped:
        candidates.append(stripped)

    seen = set()
    unique = []
    for candidate in candidates:
        if not candidate or candidate in seen:
            continue
        seen.add(candidate)
        unique.append(candidate)
    return unique


def balanced_json_object_candidates(text: str) -> list[str]:
    candidates = []
    start = None
    depth = 0
    in_string = False
    escaped = False
    for i, ch in enumerate(text):
        if in_string:
            if escaped:
                escaped = False
            elif ch == '\\':
                escaped = True
            elif ch == '"':
                in_string = False
            continue

        if ch == '"':
            in_string = True
        elif ch == '{':
            if depth == 0:
                start = i
            depth += 1
        elif ch == '}' and depth:
            depth -= 1
            if depth == 0 and start is not None:
                candidates.append(text[start:i + 1])
                start = None
    return candidates


def parse_json_response_candidate(candidate: str):
    candidate = candidate.strip()
    last_error = None
    for payload in (candidate, escape_json_string_control_chars(candidate)):
        try:
            return json.loads(payload)
        except json.JSONDecodeError as e:
            last_error = e
        try:
            decoder = json.JSONDecoder()
            value, end = decoder.raw_decode(payload)
            if payload[end:].strip():
                continue
            return value
        except json.JSONDecodeError as e:
            last_error = e
    if last_error:
        raise last_error
    raise ValueError('empty JSON candidate')


def escape_json_string_control_chars(text: str) -> str:
    out = []
    in_string = False
    escaped = False
    for ch in text:
        if in_string:
            if escaped:
                out.append(ch)
                escaped = False
            elif ch == '\\':
                out.append(ch)
                escaped = True
            elif ch == '"':
                out.append(ch)
                in_string = False
            elif ch == '\n':
                out.append('\\n')
            elif ch == '\r':
                out.append('\\r')
            elif ch == '\t':
                out.append('\\t')
            elif ord(ch) < 0x20:
                out.append(f'\\u{ord(ch):04x}')
            else:
                out.append(ch)
            continue

        out.append(ch)
        if ch == '"':
            in_string = True
    return ''.join(out)


def extract_unified_diff(text: str) -> str:
    fence_pattern = re.compile(r'```(?:diff|patch)?\s*\n(.*?)\n```', flags=re.S)
    for match in fence_pattern.finditer(text):
        candidate = match.group(1).strip('\n')
        if looks_like_patch(candidate):
            return candidate + '\n'
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
    return candidate + '\n' if looks_like_patch(candidate) else ''


def looks_like_patch(text: str) -> bool:
    return (
        'diff --git ' in text
        or ('--- a/' in text and '+++ b/' in text and '@@' in text)
        or ('--- ' in text and '+++ ' in text and '@@' in text)
    )


def apply_edits(pkg: Path, edits: list[dict]) -> None:
    repo=pkg/'repo'
    log(f"apply JSON edits count={len(edits)} repo={repo}")
    for i,e in enumerate(edits):
        typ=e.get('type'); rel=e.get('file')
        if not rel:
            raise ValueError(f'edit {i} missing file')
        path=repo/rel
        log(f"apply edit index={i} type={typ} file={rel}")
        if typ=='replace':
            old=e.get('find',''); new=e.get('replace','')
            text=path.read_text(encoding='utf-8')
            count=text.count(old)
            log(f"replace edit file={rel} find_bytes={len(old.encode('utf-8'))} replace_bytes={len(new.encode('utf-8'))} matches={count}")
            if count != 1:
                raise ValueError(f'replace.find count for {rel}: {count}')
            path.write_text(text.replace(old,new), encoding='utf-8')
        elif typ=='write':
            if path.exists():
                raise ValueError(f'write target already exists: {rel}')
            path.parent.mkdir(parents=True, exist_ok=True)
            log(f"write edit file={rel} content_bytes={len(e.get('content','').encode('utf-8'))}")
            path.write_text(e.get('content',''), encoding='utf-8')
        else:
            raise ValueError(f'unknown edit type: {typ}')


def materialize_model_patch(pkg: Path, run_dir: Path, model_patch_name: str, allow_json: bool = False) -> list[str]:
    log(f"materialize patch start run_dir={run_dir}")
    raw=(run_dir/'raw_response.txt').read_text(encoding='utf-8')
    log(f"raw response loaded bytes={len(raw.encode('utf-8'))}")
    patch_text=extract_unified_diff(raw)
    reset_repo(pkg)
    if patch_text:
        log(f"unified diff extracted bytes={len(patch_text.encode('utf-8'))}")
        candidate_patch=run_dir/'candidate.patch'
        candidate_patch.write_text(patch_text, encoding='utf-8')
        code,out=apply_patch(pkg, candidate_patch)
        if code:
            raise RuntimeError('model patch did not apply while materializing: ' + out.strip())
        prepare_untracked_files_for_diff(pkg)
        _, diff=run(['git','-C',str(pkg/'repo'),'diff','--binary'], pkg)
        (run_dir/model_patch_name).write_text(diff, encoding='utf-8')
        _, ns=run(['git','-C',str(pkg/'repo'),'diff','--name-only'], pkg)
        if not diff.strip():
            raise RuntimeError('model patch is empty')
        files = [x for x in ns.splitlines() if x]
        log(f"model patch materialized from diff changed_files={files} patch_bytes={len(diff.encode('utf-8'))}")
        return files

    if not allow_json:
        raise RuntimeError('no unified diff found in model response')

    log("no direct unified diff found; parsing JSON response")
    parsed=extract_json(raw)
    (run_dir/'parsed.json').write_text(json.dumps(parsed, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    if isinstance(parsed.get('patch'), str) and looks_like_patch(parsed['patch']):
        log(f"JSON patch field detected bytes={len(parsed['patch'].encode('utf-8'))}")
        candidate_patch=run_dir/'candidate.patch'
        candidate_patch.write_text(parsed['patch'].strip() + '\n', encoding='utf-8')
        code,out=apply_patch(pkg, candidate_patch)
        if code:
            raise RuntimeError('model patch did not apply while materializing: ' + out.strip())
    else:
        apply_edits(pkg, parsed.get('edits', []))
    prepare_untracked_files_for_diff(pkg)
    _, diff=run(['git','-C',str(pkg/'repo'),'diff','--binary'], pkg)
    (run_dir/model_patch_name).write_text(diff, encoding='utf-8')
    if not diff.strip():
        raise RuntimeError('model patch is empty')
    _, ns=run(['git','-C',str(pkg/'repo'),'diff','--name-only'], pkg)
    files = [x for x in ns.splitlines() if x]
    log(f"model patch materialized from JSON changed_files={files} patch_bytes={len(diff.encode('utf-8'))}")
    return files


def apply_patch(pkg: Path, patch: Path) -> tuple[int, str]:
    log(f"git apply start patch={patch}")
    return run(['git', '-C', str(pkg / 'repo'), 'apply', str(patch)], pkg)


def prepare_untracked_files_for_diff(pkg: Path) -> None:
    log("git add intent-to-add for diff")
    run(['git', '-C', str(pkg / 'repo'), 'add', '-N', '.'], pkg)


def validate_baseline(pkg: Path, task: dict) -> tuple[bool, str]:
    log("baseline validation start")
    reset_repo(pkg)
    lines = ['$ git apply test.patch']
    code, out = apply_patch(pkg, pkg / 'patches/test.patch')
    lines.append(out)
    if code:
        reset_repo(pkg)
        log("baseline validation failed: test.patch did not apply")
        return False, '\n'.join(lines + ['baseline invalid: test patch did not apply'])
    cmd = first_cmd(task, 'fail_to_pass')
    if not cmd:
        reset_repo(pkg)
        log("baseline validation failed: missing fail_to_pass command")
        return False, '\n'.join(lines + ['baseline invalid: missing fail_to_pass command'])
    lines.append('$ ' + cmd)
    log(f"baseline fail_to_pass command start cmd={cmd}")
    code, out = run(['bash', '-lc', cmd], pkg / 'repo')
    lines.append(out)
    reset_repo(pkg)
    if is_infrastructure_failure(out):
        log("baseline validation failed: infrastructure failure")
        return False, '\n'.join(lines + ['baseline invalid: infrastructure failure'])
    if code == 0:
        log("baseline validation failed: fail_to_pass unexpectedly passed on base")
        return False, '\n'.join(lines + ['baseline invalid: fail_to_pass unexpectedly passed on base'])
    log("baseline validation passed")
    return True, '\n'.join(lines + ['baseline_check_ok'])


def unique_output_dir(base: Path) -> Path:
    if not base.exists() or not any(base.iterdir()):
        log(f"output directory selected path={base}")
        return base
    stamp = time.strftime('%Y%m%d-%H%M%S')
    candidate = base.with_name(f'{base.name}_{stamp}')
    idx = 2
    while candidate.exists():
        candidate = base.with_name(f'{base.name}_{stamp}_{idx}')
        idx += 1
    log(f"requested output directory already populated; selected unique path={candidate}")
    return candidate


def default_prompt(pkg: Path) -> str:
    return agentic_prompt(pkg)


def agentic_prompt(pkg: Path) -> str:
    log(f"building agentic prompt package={pkg}")
    task=json.loads((pkg/'task.json').read_text(encoding='utf-8'))
    ps = standard_problem_statement(task, pkg)
    sections: list[str] = [
        f'You are solving a SWE-bench Pro task in repository {task.get("repo")}.',
        '',
        'You can inspect the base repository by requesting one tool action at a time.',
        'Return ONLY a JSON object for each response. Do not use Markdown.',
        '',
        'Available actions:',
        '{"action":"list_dir","path":"relative/path"}',
        '{"action":"read_file","path":"relative/path","start":1,"end":200}',
        '{"action":"search","query":"literal or regex","path":"relative/path"}',
        '{"action":"finish","edits":[{"type":"replace","file":"src/path","find":"exact old text","replace":"new text"}]}',
        '{"action":"finish","patch":"diff --git ..."}',
        '',
        'Rules:',
        '- Inspect the repository before finishing.',
        '- Edit production code only.',
        '- Do not edit tests or generated files.',
        '- The final answer must be a finish action with either JSON edits or a unified diff patch.',
        '',
        'Task metadata:',
        f'- instance_id: {task.get("instance_id") or task.get("task_id") or ""}',
        f'- repo: {task.get("repo")}',
        f'- base_commit: {task.get("base_commit") or ""}',
        f'- repo_language: {task.get("repo_language") or ""}',
        '',
        'Problem statement:',
        ps,
    ]
    return '\n'.join(sections).rstrip() + '\n'


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


def standard_problem_statement(task: dict, pkg: Path) -> str:
    text = str(task.get('problem_statement') or '')
    if not text.strip() and (pkg / 'problem_statement.md').is_file():
        text = (pkg / 'problem_statement.md').read_text(encoding='utf-8')
    issue = sanitize_model_input_text(text, remove_generated_sections=True)
    sections = [issue] if issue else []

    requirements = clean_optional_dataset_field(task.get('requirements'))
    if requirements and not contains_model_section(issue, 'Requirements'):
        sections.extend(['Requirements:', requirements])

    interface = clean_optional_dataset_field(task.get('interface'))
    if interface and not contains_model_section(issue, 'New interfaces introduced'):
        sections.extend(['New interfaces introduced:', interface])

    return '\n\n'.join(sections).strip() + '\n'


def contains_model_section(text: str, title: str) -> bool:
    pattern = rf'(^|\n)\s*(?:#+\s*)?{re.escape(title)}\s*:'
    return bool(re.search(pattern, text or '', flags=re.I))


def clean_optional_dataset_field(value) -> str:
    original = str(value or '')
    if contains_forbidden_model_input(original):
        return ''
    text = sanitize_model_input_text(original, remove_generated_sections=False)
    if contains_forbidden_model_input(text):
        return ''
    return text


def sanitize_model_input_text(text: str, remove_generated_sections: bool) -> str:
    if remove_generated_sections:
        text = remove_section(text, 'Background')
        for heading in ['Test Coverage', 'Constraints']:
            text = remove_section(text, heading)
    for heading in ['Expected Behavior', 'Test Coverage', 'Constraints', 'Requirements', 'Interface']:
        text = text.split(f'\n## {heading}', 1)[0]
    cleaned = []
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


def contains_forbidden_model_input(text: str) -> bool:
    return any(re.search(pattern, text or '', flags=re.I) for pattern in MODEL_INPUT_FORBIDDEN_PATTERNS)


def remove_section(text: str, heading: str) -> str:
    pattern = re.compile(rf'\n## {re.escape(heading)}\n.*?(?=\n## |\Z)', flags=re.S)
    return pattern.sub('\n', text)


def call_openai_model(base_url: str, api_key: str, model: str, prompt: str, max_tokens: int, temperature: float, enable_thinking: bool | None, timeout: int) -> tuple[str, dict]:
    url=base_url.rstrip('/') + '/chat/completions'
    payload={'model':model,'messages':[{'role':'user','content':prompt}],'temperature':temperature,'max_tokens':max_tokens}
    if enable_thinking is not None:
        payload['enable_thinking']=enable_thinking
    log(f"OpenAI-compatible request start url={url} model={model} prompt_bytes={len(prompt.encode('utf-8'))} max_tokens={max_tokens} temperature={temperature} enable_thinking={enable_thinking} timeout={timeout}")
    start = time.monotonic()
    req=urllib.request.Request(url, data=json.dumps(payload).encode(), headers={'Authorization':f'Bearer {api_key}','Content-Type':'application/json'}, method='POST')
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data=json.loads(resp.read().decode('utf-8'))
    msg=data['choices'][0]['message']
    content = msg.get('content') or ''
    usage = data.get('usage', {})
    log(f"OpenAI-compatible request done elapsed={time.monotonic()-start:.2f}s response_bytes={len(content.encode('utf-8'))} usage={usage}")
    return content, usage


def call_anthropic_sdk(base_url: str, api_key: str, model: str, prompt: str, max_tokens: int, timeout: int) -> tuple[str, dict]:
    from anthropic import Anthropic

    log(f"Anthropic SDK request start base_url={base_url.rstrip('/')} model={model} prompt_bytes={len(prompt.encode('utf-8'))} max_tokens={max_tokens} timeout={timeout}")
    start = time.monotonic()
    client=Anthropic(auth_token=api_key, base_url=base_url.rstrip('/'), timeout=timeout, max_retries=0)
    message=client.messages.create(model=model, messages=[{'role':'user','content':prompt}], max_tokens=max_tokens)
    content=''.join(getattr(part, 'text', '') for part in getattr(message, 'content', []) if getattr(part, 'text', ''))
    usage=getattr(message, 'usage', None)
    if usage and hasattr(usage, 'model_dump'):
        usage=usage.model_dump()
    log(f"Anthropic SDK request done elapsed={time.monotonic()-start:.2f}s response_bytes={len(content.encode('utf-8'))} usage={usage or {}}")
    return content, usage or {}


def call_anthropic_cli(base_url: str, api_key: str, model: str, prompt: str, timeout: int) -> tuple[str, dict]:
    log(f"Claude CLI request start base_url={base_url} model={model} prompt_bytes={len(prompt.encode('utf-8'))} timeout={timeout}")
    start = time.monotonic()
    env=os.environ.copy()
    env.update({
        'ANTHROPIC_AUTH_TOKEN': api_key,
        'ANTHROPIC_BASE_URL': base_url,
        'ANTHROPIC_MODEL': model,
        'ANTHROPIC_DEFAULT_HAIKU_MODEL': model,
        'ANTHROPIC_DEFAULT_OPUS_MODEL': model,
        'ANTHROPIC_DEFAULT_SONNET_MODEL': model,
    })
    p=subprocess.run(
        ['claude','-p','--model',model,'--output-format','json','--no-session-persistence','--tools',''],
        input=prompt,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=timeout,
        env=env,
    )
    if p.returncode != 0:
        output_preview = p.stdout[:500].replace(chr(10), '\\n')
        log(f"Claude CLI request failed exit_code={p.returncode} elapsed={time.monotonic()-start:.2f}s output_preview={output_preview}")
        raise RuntimeError(f'Claude CLI exited {p.returncode}: {p.stdout[:500]}')
    data=json.loads(p.stdout)
    content = data.get('result') or ''
    usage = data.get('usage', {})
    log(f"Claude CLI request done elapsed={time.monotonic()-start:.2f}s response_bytes={len(content.encode('utf-8'))} usage={usage}")
    return content, usage


def call_anthropic_model(base_url: str, api_key: str, model: str, prompt: str, max_tokens: int, temperature: float, timeout: int, call_mode: str) -> tuple[str, dict]:
    log(f"Anthropic call mode selected mode={call_mode}")
    if call_mode == 'sdk':
        return call_anthropic_sdk(base_url, api_key, model, prompt, max_tokens, timeout)
    if call_mode == 'cli':
        return call_anthropic_cli(base_url, api_key, model, prompt, timeout)
    try:
        return call_anthropic_sdk(base_url, api_key, model, prompt, max_tokens, timeout)
    except Exception as sdk_error:
        log(f"Anthropic SDK failed; trying Claude CLI fallback error={sdk_error}")
        try:
            return call_anthropic_cli(base_url, api_key, model, prompt, timeout)
        except Exception as cli_error:
            log(f"Anthropic CLI fallback failed error={cli_error}")
            raise RuntimeError(f'Anthropic SDK failed: {sdk_error}; Claude CLI fallback failed: {cli_error}') from sdk_error


def call_model(base_url: str, api_key: str, model: str, prompt: str, max_tokens: int, temperature: float, enable_thinking: bool | None, timeout: int, provider: str, anthropic_call_mode: str) -> tuple[str, dict]:
    log(f"model call dispatch provider={provider} model={model}")
    if provider == 'anthropic':
        return call_anthropic_model(base_url, api_key, model, prompt, max_tokens, temperature, timeout, anthropic_call_mode)
    return call_openai_model(base_url, api_key, model, prompt, max_tokens, temperature, enable_thinking, timeout)


def safe_repo_path(pkg: Path, rel: str) -> Path:
    repo = (pkg / 'repo').resolve()
    path = (repo / (rel or '.')).resolve()
    if path != repo and repo not in path.parents:
        raise ValueError(f'path escapes repository: {rel}')
    return path


def agentic_tool_observation(pkg: Path, action: dict) -> str:
    kind = str(action.get('action') or '').strip()
    if kind == 'list_dir':
        path = safe_repo_path(pkg, str(action.get('path') or '.'))
        if not path.is_dir():
            return f'ERROR: not a directory: {action.get("path")}'
        items = []
        for child in sorted(path.iterdir(), key=lambda p: (not p.is_dir(), p.name.lower()))[:200]:
            suffix = '/' if child.is_dir() else ''
            items.append(child.name + suffix)
        return '\n'.join(items) or '(empty directory)'

    if kind == 'read_file':
        path = safe_repo_path(pkg, str(action.get('path') or ''))
        if not path.is_file():
            return f'ERROR: not a file: {action.get("path")}'
        start = max(1, int(action.get('start') or 1))
        end = max(start, int(action.get('end') or start + 199))
        end = min(end, start + 399)
        lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
        out = []
        for idx in range(start, min(end, len(lines)) + 1):
            out.append(f'{idx}: {lines[idx - 1]}')
        return '\n'.join(out) or '(empty range)'

    if kind == 'search':
        query = str(action.get('query') or '').strip()
        if not query:
            return 'ERROR: missing search query'
        path = safe_repo_path(pkg, str(action.get('path') or '.'))
        cmd = ['rg', '-n', '--hidden', '--glob', '!node_modules/**', '--glob', '!vendor/**', '--glob', '!.git/**', query, str(path)]
        try:
            p = subprocess.run(cmd, cwd=pkg / 'repo', text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
        except FileNotFoundError:
            p = subprocess.run(['grep', '-RIn', query, str(path)], cwd=pkg / 'repo', text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=20)
        output = p.stdout or ''
        lines = output.splitlines()
        if len(lines) > 120:
            lines = lines[:120] + ['[TRUNCATED]']
        return '\n'.join(lines) if lines else '(no matches)'

    return f'ERROR: unknown action: {kind}'


def agentic_transcript_prompt(initial_prompt: str, transcript: list[dict]) -> str:
    sections = [initial_prompt.rstrip(), '', 'Conversation so far:']
    if not transcript:
        sections.append('(none)')
    for item in transcript:
        sections.append('')
        sections.append(f"Assistant JSON:\n{item.get('assistant', '')}")
        sections.append(f"Observation:\n{item.get('observation', '')}")
    sections.extend([
        '',
        'Return the next JSON action now. If you have enough information, return a finish action.',
    ])
    return '\n'.join(sections).rstrip() + '\n'


def run_agentic_attempt(pkg: Path, run_dir: Path, initial_prompt: str, base_url: str, api_key: str, model: str,
                        max_tokens: int, temperature: float, enable_thinking: bool | None, timeout: int,
                        provider: str, anthropic_call_mode: str, max_steps: int) -> dict:
    transcript: list[dict] = []
    usage_events: list[dict] = []
    for step in range(1, max_steps + 1):
        log(f"agentic step {step}/{max_steps} start")
        prompt = agentic_transcript_prompt(initial_prompt, transcript)
        content, usage = call_model(base_url, api_key, model, prompt, max_tokens, temperature,
                                    enable_thinking, timeout, provider, anthropic_call_mode)
        usage_events.append({'step': step, 'usage': usage})
        (run_dir / f'agent_step_{step:02d}_response.txt').write_text(content, encoding='utf-8')
        action = extract_json(content)
        (run_dir / f'agent_step_{step:02d}_action.json').write_text(
            json.dumps(action, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        if action.get('action') == 'finish':
            (run_dir / 'raw_response.txt').write_text(json.dumps(action, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            (run_dir / 'usage.json').write_text(json.dumps({'agentic_steps': usage_events}, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            log(f"agentic finish received step={step}")
            return eval_one(pkg, run_dir, initial_prompt, model=model, allow_json=True)
        observation = agentic_tool_observation(pkg, action)
        transcript.append({
            'assistant': json.dumps(action, ensure_ascii=False),
            'observation': observation,
        })
        (run_dir / f'agent_step_{step:02d}_observation.txt').write_text(observation, encoding='utf-8')
        log(f"agentic step {step}/{max_steps} observation_bytes={len(observation.encode('utf-8'))}")
    raise RuntimeError(f'agentic model did not finish within {max_steps} steps')


def eval_one(pkg: Path, run_dir: Path, prompt: str, model_patch_name='model.patch', model: str = '', allow_json: bool = False) -> dict:
    log(f"eval run start run_dir={run_dir}")
    result={'model_patch_applied':False,'test_patch_applied':False,'fail_to_pass_passed':False,'pass_to_pass_passed':False,'passed':False,'error':None,'files_changed':[]}
    eval_lines=[]
    try:
        task=json.loads((pkg/'task.json').read_text(encoding='utf-8'))
        result['files_changed']=materialize_model_patch(pkg, run_dir, model_patch_name, allow_json=allow_json)
        eval_lines.append('files_changed: ' + ', '.join(result['files_changed']))
        log(f"eval run files_changed={result['files_changed']}")
        reset_repo(pkg)
        eval_lines.append('$ git apply model.patch')
        code,out=apply_patch(pkg, run_dir/model_patch_name); eval_lines.append(out)
        if code: raise RuntimeError('model patch did not apply')
        result['model_patch_applied']=True
        log("model patch applied for fail-to-pass phase")
        eval_lines.append('$ git apply test.patch')
        code,out=apply_patch(pkg, pkg/'patches/test.patch'); eval_lines.append(out)
        if code: raise RuntimeError('test patch did not apply')
        result['test_patch_applied']=True
        log("test patch applied")
        cmd=first_cmd(task, 'fail_to_pass')
        if cmd:
            eval_lines.append('$ '+cmd)
            log(f"fail_to_pass start cmd={cmd}")
            code,out=run(['bash','-lc',cmd], pkg/'repo'); eval_lines.append(out)
            if is_infrastructure_failure(out):
                raise RuntimeError('fail_to_pass infrastructure failure')
            result['fail_to_pass_passed']=code==0
            log(f"fail_to_pass done passed={result['fail_to_pass_passed']} exit_code={code}")
        else:
            log("fail_to_pass command missing")
        reset_repo(pkg)
        code,out=apply_patch(pkg, run_dir/model_patch_name)
        eval_lines.append('$ git apply model.patch # pass-to-pass'); eval_lines.append(out)
        if code==0:
            log("model patch applied for pass-to-pass phase")
            cmd=first_cmd(task, 'pass_to_pass')
            if cmd:
                eval_lines.append('$ '+cmd)
                log(f"pass_to_pass start cmd={cmd}")
                code,out=run(['bash','-lc',cmd], pkg/'repo'); eval_lines.append(out)
                if is_infrastructure_failure(out):
                    raise RuntimeError('pass_to_pass infrastructure failure')
                result['pass_to_pass_passed']=code==0
                log(f"pass_to_pass done passed={result['pass_to_pass_passed']} exit_code={code}")
            else:
                log("pass_to_pass command missing")
        else:
            log(f"model patch failed during pass-to-pass reapply exit_code={code}")
        result['passed']=all([result['model_patch_applied'],result['test_patch_applied'],result['fail_to_pass_passed'],result['pass_to_pass_passed']])
        log(f"eval run done passed={result['passed']} result={result}")
    except Exception as e:
        result['error']=str(e)
        eval_lines.append('ERROR: ' + str(e))
        log(f"eval run error={e}")
    finally:
        (run_dir/'eval.log').write_text('\n'.join(eval_lines)+('\n' if eval_lines else ''), encoding='utf-8')
        log(f"eval log written path={run_dir/'eval.log'} bytes={(run_dir/'eval.log').stat().st_size}")
        write_evaluation_artifacts(pkg, run_dir, result, model)
        reset_repo(pkg)
    return result


def main() -> int:
    ap=argparse.ArgumentParser(description='Run OpenAI-compatible model evaluations for a SWE-Pro package.')
    ap.add_argument('package_dir')
    ap.add_argument('--model', required=True)
    ap.add_argument('--base-url', required=True, help='e.g. https://dashscope.aliyuncs.com/compatible-mode/v1')
    ap.add_argument('--api-key-env', required=True)
    ap.add_argument('--attempts', type=int, required=True)
    ap.add_argument('--out-name', required=True, help='model_evaluation/<out-name>')
    ap.add_argument('--prompt-file')
    ap.add_argument('--agent-max-steps', type=int, default=20)
    ap.add_argument('--max-tokens', type=int, default=4096)
    ap.add_argument('--temperature', type=float, default=0.7)
    ap.add_argument('--timeout', type=int, default=600)
    ap.add_argument('--enable-thinking', choices=['true','false','omit'], default='omit')
    ap.add_argument('--provider', choices=['openai', 'anthropic'], default='openai')
    ap.add_argument('--anthropic-call-mode', choices=sorted(ANTHROPIC_CALL_MODES), default=os.environ.get('ANTHROPIC_CALL_MODE', 'auto'))
    args=ap.parse_args()
    started = time.monotonic()
    pkg=Path(args.package_dir).resolve()
    log(
        f"evaluation start package={pkg} model={args.model} provider={args.provider} "
        f"attempts={args.attempts} out_name={args.out_name} base_url={args.base_url} "
        f"api_key_env={args.api_key_env} api_key_present={bool(os.environ.get(args.api_key_env))} "
        f"max_tokens={args.max_tokens} temperature={args.temperature} timeout={args.timeout} "
        f"enable_thinking={args.enable_thinking} anthropic_call_mode={args.anthropic_call_mode}"
    )
    api_key=os.environ.get(args.api_key_env)
    if not api_key:
        log(f"missing API key env={args.api_key_env}")
        raise SystemExit(f'missing API key env: {args.api_key_env}')
    if args.prompt_file:
        log(f"loading prompt file path={args.prompt_file}")
        prompt=Path(args.prompt_file).read_text(encoding='utf-8')
    else:
        prompt=default_prompt(pkg)
    log(f"prompt ready bytes={len(prompt.encode('utf-8'))}")
    requested_out=pkg/'model_evaluation'/args.out_name
    out=unique_output_dir(requested_out)
    out.mkdir(parents=True, exist_ok=True)
    log(f"output directory ready path={out}")
    task=json.loads((pkg/'task.json').read_text(encoding='utf-8'))
    log(f"task metadata repo={task.get('repo')} instance_id={task.get('instance_id')} language={task.get('language')}")
    baseline_ok, baseline_log = validate_baseline(pkg, task)
    (out/'baseline_check.log').write_text(baseline_log + '\n', encoding='utf-8')
    log(f"baseline log written path={out/'baseline_check.log'} ok={baseline_ok}")
    if not baseline_ok:
        raise SystemExit('baseline check failed; see baseline_check.log')
    enable = None if args.enable_thinking == 'omit' else args.enable_thinking == 'true'
    results=[]
    for i in range(1,args.attempts+1):
        log(f"attempt {i}/{args.attempts} start")
        rd=out/f'run_{i:02d}'; rd.mkdir(parents=True, exist_ok=True)
        (rd/'prompt.md').write_text(prompt, encoding='utf-8')
        log(f"attempt {i}/{args.attempts} prompt written path={rd/'prompt.md'}")
        result={'attempt':i}
        try:
            result.update(run_agentic_attempt(
                pkg, rd, prompt, args.base_url, api_key, args.model, args.max_tokens,
                args.temperature, enable, args.timeout, args.provider, args.anthropic_call_mode,
                args.agent_max_steps))
        except urllib.error.HTTPError as e:
            body=e.read().decode('utf-8','replace')
            (rd/'raw_response.txt').write_text(body, encoding='utf-8')
            result.update({'passed':False,'error':f'HTTPError {e.code}: {body[:500]}'})
            (rd/'eval.log').write_text('ERROR: ' + result['error'] + '\n', encoding='utf-8')
            write_evaluation_artifacts(pkg, rd, result, args.model)
            body_preview = body[:500].replace(chr(10), '\\n')
            log(f"attempt {i}/{args.attempts} HTTPError code={e.code} body_preview={body_preview}")
        except Exception as e:
            result.update({'passed':False,'error':str(e)})
            (rd/'eval.log').write_text('ERROR: ' + str(e) + '\n', encoding='utf-8')
            write_evaluation_artifacts(pkg, rd, result, args.model)
            log(f"attempt {i}/{args.attempts} error={e}")
        results.append(result)
        print(f"run_{i:02d}: {'PASS' if result.get('passed') else 'FAIL'}" + (f" ({result.get('error')})" if result.get('error') else ''), flush=True)
        log(f"attempt {i}/{args.attempts} complete result={json.dumps(result, ensure_ascii=False)}")
        time.sleep(1)
    passes=sum(1 for r in results if r.get('passed'))
    status_counts = {status: 0 for status in sorted(STANDARD_EVALUATION_STATUSES)}
    for result in results:
        status = result.get('status') or standard_status(result)
        if status not in status_counts:
            status_counts[status] = 0
        status_counts[status] += 1
    summary={'model':args.model,'base_url':args.base_url,'requested_out_name':args.out_name,'output_dir':str(out.relative_to(pkg)),'attempts':args.attempts,'passes':passes,'pass_rate':passes/args.attempts,'status_counts':status_counts,'standard_statuses':sorted(STANDARD_EVALUATION_STATUSES),'pass_nonzero':passes>0,'pass_rate_lte_50_percent':passes/args.attempts <= 0.5,'results':results}
    (out/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    sync_task_model_evaluation(pkg, summary)
    log(f"summary written path={out/'summary.json'} passes={passes} attempts={args.attempts} pass_rate={passes/args.attempts} elapsed={time.monotonic()-started:.2f}s")
    print(json.dumps({'passes':passes,'attempts':args.attempts,'pass_rate':passes/args.attempts}, ensure_ascii=False))
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
