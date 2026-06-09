---
name: tb20-dataset-production
description: Use this skill to produce Terminal-Bench 2.0 dataset source material from stable, licensed domain channels. It focuses on the core production stage from brief/spec input to demo-grade instruction.md and test-generation-brief.md. It must use fixed domain source channels, source evidence, license evidence, material dossiers, and problem cards; it must not invent tasks from keyword search or generate placeholder tasks. Use tb20-batch-execution-delivery only after full datasets exist.
---

# TB2.0 Dataset Production

This skill currently owns the core production stage. The controllable workflow must be driven by scripts or backend APIs:

```text
brief/spec input -> source-backed material acquisition -> problem-card -> instruction.md -> test-generation-brief.md
```

It does not run Harbor, solve tasks, collect `agent-logs/`, or pretend a script can judge semantic task quality.

## Runtime Control

This installable skill is for Codex execution and guidance only. System and backend entrypoints live outside the skill directory under `tools/tb20-production/scripts`.

Use the toolkit script entrypoint for every controllable system step. The script starts Codex as the skill executor for the AI-heavy production step, then enforces file and quality gates.

```bash
TOOLKIT_DIR=/path/to/tools/tb20-production
TB20_VENV=/home/ubuntu/tb20-runtime/.venv
"$TB20_VENV/bin/python" "$TOOLKIT_DIR/scripts/tb20_dataset.py" prepare-instruction \
  --workspace <workspace> \
  --output-root <candidate-output-root> \
  --domain software-engineering \
  --source-channel github-pr-mining \
  --brief-file <brief.md> \
  --channel-config '{"allowedForTaskGeneration":true,"sourceName":"...","sourceUrl":"...","license":"MIT","termsUrl":"...","adapterType":"codex","codexBinary":"codex","codexSkillSyncMode":"symlink","codexModel":"..."}'
```

Exit code contract:

- `0`: required files are present and pass the instruction/test-brief gate.
- `2`: controlled block; evidence is written and production must not proceed.
- any other non-zero code: script/runtime failure.

Default execution uses `codex exec` with the real Codex skill discovery path. The script synchronizes this skill into `$CODEX_HOME/skills/tb20-dataset-production` (or `~/.codex/skills/tb20-dataset-production`), writes `codex-contract.json` and `codex-request.md`, then starts a new Codex process whose request explicitly uses `$tb20-dataset-production`. The script still decides PASS/BLOCKED/FAIL from produced files and gate evidence.

When this skill is invoked by a request that points to `codex-contract.json`, that Codex process is already inside the script-controlled executor stage. In that mode, do not invoke `tb20_dataset.py` again and do not start another Codex process. Read the contract, perform the source-backed production work, and write the required files directly to the contract output root so the parent script can run its gates.

## Fixed Domains

Only these production domains are allowed:

```text
software-engineering
system-administration
security
data-science
scientific-computing
file-operations
web-network-services
distributed-systems
performance-optimization
algorithms-and-formats
```

Do not create an eleventh domain without an explicit user request.

## Source Channel Rule

Do not start from keyword search. Start from the selected domain and its stable source channel.

Allowed acquisition modes:

- official API
- official bulk dump or archive
- official Git repository clone
- official package/source mirror
- clearly licensed dataset catalog

Rejected by default:

- search engine scraping
- random webpage scraping
- unlicensed GitHub repositories
- blog/StackOverflow content as task source text
- paid or separate-license benchmarks
- no-license, non-commercial, research-only, or no-derivatives content

Default license allowlist:

```text
MIT
BSD-2-Clause
BSD-3-Clause
Apache-2.0
ISC
CC0
CC-BY-4.0
public-domain
US-government-public-data
```

GPL/LGPL/MPL sources may be studied, but do not copy code/tests into deliverables unless the downstream license obligations are intentionally accepted.

## Competitive Sample Standard

When the task is meant as a client-facing bid or demo sample, do not choose a scenario that is close to official Terminal-Bench examples or common web-server/logging demos unless the user explicitly asks for that domain.

Prefer scenarios with:

- a formal, stable specification or public-domain/open-license canonical dataset
- realistic terminal work beyond simple text aggregation
- binary formats, protocol edge cases, recovery, validation, numerical tolerances, schedulers, parsers, or multi-file audits
- a clear difficulty gradient where `easy`, `medium`, and `hard` test different capabilities rather than larger copies of the same task
- hidden-test potential that does not rely on brittle exact fixture answers

Avoid:

- Nginx/Apache setup tasks that resemble official demos
- generic access-log counting unless it is only a small subcomponent of a larger scenario
- tasks where a one-screen script or obvious grep pipeline solves all three difficulties
- invented domains without source/license grounding

## Channel Matrix

Use these channels as the first implementation target:

| Domain | Stable source channels | Mining purpose |
|---|---|---|
| `software-engineering` | GitHub API, GH Archive, Software Heritage, Libraries.io | PR/issue/test-diff grounded bugfix, feature, regression, parser/CLI subset |
| `system-administration` | Debian source packages, Debian Policy, Linux man-pages, systemd/Kubernetes repos | service config, logs, packages, permissions, processes |
| `security` | NVD API, CVE cvelistV5, CWE, Exploit-DB, Vulhub | defensive reproduction, log forensics, weak config detection, protocol/crypto misuse |
| `data-science` | UCI ML Repository, OpenML, data.gov metadata, limited Common Crawl discovery | cleaning, validation, aggregation, schema conversion, statistical reports |
| `scientific-computing` | Netlib, NIST StRD, SuiteSparse Matrix Collection, SciPy/NumPy tests | numerical algorithms, matrix/signal problems, tolerance-driven verification |
| `file-operations` | GNU coreutils, libarchive, rsync, Debian archive docs, POSIX specs | traversal, archive/checksum, sync, backup retention, mtime/permission semantics |
| `web-network-services` | RFC Editor, IANA registries, W3C/WHATWG specs, curl/Apache/Nginx docs | HTTP/DNS/TLS/metrics/server behavior and protocol boundaries |
| `distributed-systems` | CNCF Landscape, Kubernetes/etcd/Prometheus/Kafka/Redis/RabbitMQ docs/tests, Jepsen analyses | consistency, retry, leader election, queue semantics, recovery, config failure |
| `performance-optimization` | LLVM test-suite, Google Benchmark, Phoronix Test Suite, open NAS/PolyBench-style suites | algorithmic/caching/IO/concurrency performance tasks |
| `algorithms-and-formats` | RFC/IANA specs, Netlib, Rosetta Code, CP-algorithms, zlib/png/sqlite/libarchive specs/repos | parsers, encoders/decoders, binary formats, checksums, graph/format algorithms |

## Required Outputs

For each candidate task, produce a workspace with:

```text
source.json
license.txt
acquisition.log
materials.md
problem-card.md
instruction.md
test-generation-brief.md
```

`source.json` must include:

```json
{
  "domain": "software-engineering",
  "source_channel": "github-pr-mining",
  "acquisition_method": "GitHub REST API + git clone",
  "source_name": "",
  "source_url": "",
  "license": "",
  "license_url": "",
  "terms_url": "",
  "redistribution_risk": "low|medium|high",
  "allowed_for_task_generation": true
}
```

If the license or terms cannot be established, stop with `allowed_for_task_generation=false`.

## Instruction Standard

`instruction.md` must be demo-grade: a test writer should not need to guess the core task semantics.

Use this structure:

```markdown
# <Task Title>

## Context
## Files Available
## Task
## Input Format
## Required Output
## Behavioral Requirements
## Edge Cases
## Constraints
## Examples
## Success Criteria
```

Required clarity:

- exact `/app` paths
- file formats and schemas
- output types, sorting, precision, tolerance, units, accepted equivalent renderings, and newline rules
- allowed and forbidden actions
- edge cases that should drive tests
- success criteria that can become verifier assertions

Do not leak hidden answers or copy upstream implementation code.

If a verifier will require an exact string, unit, timestamp rendering, error-code spelling, or offset format, that exact requirement must appear in `instruction.md`. If the instruction only asks for a semantic value, the verifier must accept semantically equivalent renderings or the instruction must be tightened before evaluation.

## Test Generation Brief

`test-generation-brief.md` is the handoff to the later test stage. It must contain:

```text
Observable outputs
Fixture plan
Normal behavior tests
Boundary tests
Adversarial/wrong-solution tests
Old behavior preservation tests, if applicable
Hidden-test strategy
Wrong implementations to reject
```

This brief must be traceable to `materials.md`, `problem-card.md`, and `instruction.md`.

## Verifier Quality Rules

The verifier must check externally specified behavior, not an internal reference parser's incidental wording. In particular:

- Do not require exact exception or error-reason strings unless the task explicitly specifies them.
- Accept equivalent units or formats when the instruction permits them; otherwise update the instruction first.
- Use focused assertions that reveal behavior gaps: corrupt input handling, boundary transitions, duplicate detection, sort order, precision/tolerance, and trailing-newline rules.
- Run the oracle solution before any agent evaluation and fix verifier/spec mismatches immediately.
- Keep task data synthetic or source-grounded; do not copy upstream fixtures unless the license and redistribution risk are intentionally accepted.

For Docker image tags in shell commands, always brace variables in zsh-compatible contexts, for example `"repo/task-${difficulty}:local"`, not `"repo/task-$difficulty:local"`. zsh treats `$name:modifier` specially and can silently create wrong tags.

## Software Engineering Mining

For `software-engineering`, do not use GitHub keyword search. Use event/repo structure:

```text
repo allowlist/license check
-> merged PRs
-> PRs with tests changed
-> PRs with source + tests changed
-> linked issue or explicit PR problem statement
-> diff size and dependency reproducibility filter
-> parent commit checkout
-> problem-card
-> instruction.md
```

Use PR tests and issue/PR descriptions as behavior anchors; use fix diff only to estimate complexity and avoid leaking solution details.

## Reporting

Report:

```text
Domain:
Source channel:
Workspace:
Instruction:
Test brief:
Blocked reason, if any:
```
