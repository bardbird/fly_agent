# SWE-Pro Pipeline Gap Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair SWE-Pro candidate collection and evaluation gates so the pipeline is issue-grounded, explicit about oracle/dedup status, proxied, no longer uses GPT to guide Opus retries, and generates the accepted 34-rule self-check workbook.

**Architecture:** Keep the existing Java service boundary and external toolkit integration. Add candidate metadata fields and small helper methods in the existing SWE services rather than introducing a new subsystem. Tests use direct service/helper behavior and reflection only where current methods are private.

**Tech Stack:** Java 17, Spring Boot WebClient, MyBatis Plus entities, FastJSON2, JUnit 5, Mockito.

---

### Task 1: Add Test Harness

**Files:**
- Modify: `fly-agent-service/pom.xml`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/swe/SwePipelineServiceTest.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/swe/GithubPullCandidateServiceTest.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/swe/SweAcceptanceReportServiceTest.java`

- [x] Add JUnit 5 and Mockito test dependencies to service module.
- [x] Configure Maven Surefire 3.2.5 so JUnit 5 tests are actually discovered.
- [x] Create tests before production changes.
- [x] Run selected tests and confirm they fail for missing behavior before implementation.

### Task 2: Issue-Grounded Candidate Collection

**Files:**
- Modify: `fly-agent-common/src/main/java/com/fly/agent/common/dto/swe/GithubPullCandidateDTO.java`
- Modify: `fly-agent-common/src/main/java/com/fly/agent/common/dto/swe/GithubPullScanResponse.java`
- Modify: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/swe/SweCandidateEntity.java`
- Add: `fly-agent-dao/src/main/resources/db/migration/V5__swe_candidate_issue_oracle_fields.sql`
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/GithubPullCandidateService.java`

- [x] Add fields for issue URL/numbers/problem statement/hints/test oracle and dedup status.
- [x] Extract resolved issue numbers from PR title/body using GitHub closing keywords.
- [x] Fetch issues and build problem statement.
- [x] Skip PRs without resolved issue references.
- [x] Persist and map new fields.

### Task 3: Pipeline CSV and Dedup Statuses

**Files:**
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SwePipelineService.java`

- [x] Include issue and oracle fields in candidate CSV.
- [x] Return explicit dedup status text instead of pending-as-pass.
- [x] Keep delivered candidates as blocking failures.

### Task 4: Remove GPT-Assisted Opus Retry

**Files:**
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SwePipelineService.java`
- Delete: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SwePromptService.java`
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SweProperties.java`
- Modify: `fly-agent-server/src/main/resources/application.yml`
- Modify: `fly-agent-server/src/main/resources/application-dev.yml`

- [x] Replace `runOpusEvaluationWithGptRetry` with direct Opus evaluation.
- [x] Remove GPT config requirement from environment check.
- [x] Stop writing GPT failure review, retry prompt, and GPT retry cost artifacts.
- [x] Remove `swe.gpt` properties and YAML configuration.

### Task 5: GitHub Proxy Consistency

**Files:**
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/GithubPullCandidateService.java`
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/GithubRepositorySearchService.java`

- [x] Build GitHub WebClients with Reactor Netty proxy when `127.0.0.1:7897` is reachable.
- [x] Keep behavior unchanged when proxy is unavailable.

### Task 6: Acceptance Self-Check Report

**Files:**
- Add: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SweAcceptanceReportService.java`
- Test: `fly-agent-service/src/test/java/com/fly/agent/service/swe/SweAcceptanceReportServiceTest.java`
- Modify: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SwePipelineService.java`

- [x] Generate `乙方质检-SWE-Pro数据验收标准对照表.xlsx` during QC inspection.
- [x] Match the accepted sample workbook shape: `34条验收结果` and `汇总`.
- [x] Validate 35 rows in the result sheet and 11 rows in the summary sheet.

### Task 7: Verification

**Files:**
- All changed files.

- [x] Run `mvn -pl fly-agent-service -am test`.
- [x] Run `mvn -pl fly-agent-server -am -DskipTests package` if tests pass.
- [x] Run static search to verify GPT-assisted Opus retry references are gone from production code and resources.
- [x] Inspect `git diff` for accidental secret/config churn.
