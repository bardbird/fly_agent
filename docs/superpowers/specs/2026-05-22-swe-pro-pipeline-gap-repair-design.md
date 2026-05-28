# SWE-Pro Pipeline Gap Repair Design

## Goal

Repair the SWE-Pro automated collection pipeline so candidates are issue-grounded, oracle-aware, deduplicated with explicit status, consistently proxied through the GitHub proxy, free of GPT-assisted Opus retry behavior, and able to produce the accepted 34-rule self-check workbook.

## Scope

The change covers the Java service, DTO/entity fields, database migration, report generation, configuration, and focused unit tests. It does not replace the external SWE-Pro toolkit, but it makes the Java boundary send and persist the fields that the toolkit needs.

## Design

`GithubPullCandidateService` collects only merged PRs that reference resolved issues with GitHub closing keywords. For each accepted PR it fetches issue titles and bodies, collects issue URLs and numbers, and persists `problem_statement`, `hints_text`, `issue_url`, and `issue_numbers`. PRs without resolved issues are skipped and counted by `skippedNoResolvedIssue`.

Candidate scoring keeps the existing patch-size heuristics, but adds issue/test oracle signals: `test_patch_present`, `fail_to_pass`, and `pass_to_pass`. When Java cannot derive exact test lists before packaging, these fields remain explicit empty JSON arrays rather than implied success.

`SwePipelineService` passes issue and oracle fields into the candidate CSV. Candidate dedup no longer reports pending checks as passed; it returns explicit statuses for delivered, benchmark, and failed-history checks. Existing package inspection continues to require package artifacts.

Java GitHub clients use a proxied WebClient configuration when `127.0.0.1:7897` is available, matching the existing script environment proxy behavior.

The Opus evaluation stage runs only direct Opus attempts with `opus4.7_pass8_behavior`. The GPT failure-review and retry-prompt path was removed from the pipeline call graph, and GPT config is no longer present in `SweProperties` or application YAML.

`SweAcceptanceReportService` generates `乙方质检-SWE-Pro数据验收标准对照表.xlsx` during QC inspection when the package does not already contain it. The workbook shape matches the accepted sample: sheet `34条验收结果` has one header row plus 34 rule rows, and sheet `汇总` has 11 rows.

## Tests

Focused service tests cover resolved issue extraction, skip counting for PRs without issue references, candidate CSV issue/oracle fields, removal of GPT-assisted Opus retry references from the pipeline path, and the self-check workbook sheet/row structure.

Verified commands:
- `mvn -pl fly-agent-service -am test`
- `mvn -pl fly-agent-server -am -DskipTests package`
