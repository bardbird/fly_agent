# AI Review Prompts

Use these prompts when asking another model/agent or Codex pass to review artifacts.

## Topic Review

Review this Terminal-Bench 2.0 task brief for realism, difficulty, testability, and anti-cheat risk. Return only findings grounded in the brief and proposed files. Do not assume missing evidence.

## Reference Solution Review

Review `solution/solve.sh` against `instruction.md`. Identify shortcuts, hidden dependencies, hardcoded answers, non-determinism, and missing edge cases. Do not mark it correct without a real verifier result.

## Verifier Review

Review `tests/test.sh` and `tests/test_outputs.py`. Identify weak assertions, implementation-coupled checks, hardcode vulnerabilities, missing negative cases, and reward-writing risks. Do not infer hidden test coverage.

## Delivery Audit

Review `delivery_manifest.json`, `delivery_index.md`, task directories, and evidence logs. Check for missing required files, fake-looking logs, placeholder text, secrets, and inconsistent reward/test counts.
