---
name: swe_repo_sca_discovery
description: Use this skill when running or configuring the SWE repo SCA discovery job that finds high-star GitHub repositories with sweRepoScaDiscoveryJob and records SCA/license reports.
version: 1.0.0
author: fly-agent-team
---

# SWE Repo SCA Discovery

## Purpose
Run `sweRepoScaDiscoveryJob` from `fly-agent-task/src/main/java/com/fly/agent/task/job/SweRepoDiscoveryJob.java` to discover high-star GitHub repos and persist only SCA/license reports.

## Default Target
Use these defaults unless the user overrides them:

| Group | Share | Repo Count | Languages |
| --- | ---: | ---: | --- |
| Go | 20% | 400 | `go` |
| Python | 20% | 400 | `python` |
| JS/TS | 20% | 400 | `javascript`, `typescript` split evenly |
| C/C++ | 10% | 200 | `c`, `c++` split evenly |
| Java | 10% | 200 | `java` |
| Rust | 10% | 200 | `rust` |
| Other supported | 10% | 200 | `ruby`, `php` split evenly |

Supported languages come from `GithubRepositorySearchService.LANGUAGE_MAP`: `c`, `c++`, `ruby`, `rust`, `go`, `javascript`, `php`, `typescript`, `python`, `java`.

## Job Constraints
- `githubToken` is required by `requireGithubToken`; do not trigger without a real token.
- `repoLimit` is clamped to max 200 by `parseScanRequest`, so quotas over 200 must be split into multiple job payloads.
- `repositoryPerPage` is clamped to max 50 and `repositoryPages` to max 10. Use `repositoryPages = ceil(repoLimit / 50)`.
- Repository search is already `sort=stars`, `order=desc`; keep `useStarCursor=true` so repeated batches continue from the current star cursor.
- The default star lower bound is `minStars=8000`.
- The daily limiter counts rows where `DATE(checked_at) = scanDate`; immediate bulk runs that exceed 200 for one language need split payloads with distinct `scanDate` values for the extra batches.

## Execute
Generate the default payloads:

```bash
python3 skills/custom/swe-repo-sca-discovery/scripts/run_sca_discovery.py \
  --output /tmp/swe_repo_sca_discovery_payloads.jsonl
```

Trigger the local XXL executor directly:

```bash
python3 skills/custom/swe-repo-sca-discovery/scripts/run_sca_discovery.py \
  --execute \
  --github-token "$GITHUB_TOKEN" \
  --executor-url "http://127.0.0.1:9999" \
  --access-token "${XXL_JOB_ACCESS_TOKEN:-default_token}"
```

The script writes one JSONL row per payload. In execute mode each row also records the executor trigger response.

