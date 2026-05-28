# Task SCA Report API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an API that generates task-level SCA delivery materials for an existing SWE-Pro task package.

**Architecture:** A new service owns SCA generation and validation. The REST controller exposes the service as an API. The service invokes `tools/swe-pro-production/scripts/generate_task_sca.py` with the configured Python runtime so future pipeline integration can call the same service method.

**Tech Stack:** Spring Boot 3, Java 17, MyBatis artifact records, Python 3 standard library, syft/trivy CLI when real generation runs.

---

### Task 1: Python Generator Core

**Files:**
- Create: `tools/swe-pro-production/scripts/generate_task_sca.py`
- Create: `tools/swe-pro-production/scripts/generate_task_sca_test.py`

- [ ] Write tests for SPDX JSON normalization, unknown license risk rows, LICENSE/NOTICE copy behavior, and missing scanner failure.
- [ ] Run `python3 -m unittest tools/swe-pro-production/scripts/generate_task_sca_test.py` and verify the new tests fail because the module does not exist.
- [ ] Implement the generator with `syft` preferred over `trivy`, writing `SCA_交付材料/` and required CSV/Markdown/SBOM/log files.
- [ ] Run the Python unit tests and verify they pass.

### Task 2: Java Service and DTOs

**Files:**
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/dto/swe/SweScaReportGenerateRequest.java`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/dto/swe/SweScaReportGenerateResponse.java`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SweScaReportService.java`
- Modify: `fly-agent-service/src/test/java/com/fly/agent/service/swe/SwePipelineServiceTest.java`

- [ ] Write a failing Java test showing the service invokes `generate_task_sca.py`, validates `SCA_交付材料/`, and reports generated files.
- [ ] Run the focused Maven test and verify it fails before service implementation.
- [ ] Implement request/response DTOs and `SweScaReportService.generate(...)`.
- [ ] Run the focused Maven test and verify it passes.

### Task 3: REST API

**Files:**
- Modify: `fly-agent-server/src/main/java/com/fly/agent/api/controller/swe/SwePipelineController.java`
- Modify: `fly-agent-service/src/test/java/com/fly/agent/service/swe/SwePipelineServiceTest.java`

- [ ] Write a failing source-level test that verifies `POST /api/v1/swe/sca-report/generate` exists and delegates to `SweScaReportService`.
- [ ] Implement the controller injection and endpoint.
- [ ] Run the focused Maven test and Python tests.

### Task 4: Pipeline Follow-Up

**Files:**
- Modify later: `fly-agent-common/src/main/java/com/fly/agent/common/enums/swe/SwePipelineStage.java`
- Modify later: `fly-agent-service/src/main/java/com/fly/agent/service/swe/SwePipelineService.java`

- [ ] After the API is validated with a real case, add `TASK_SCA_ANALYSIS` and call `SweScaReportService.generate(...)` from production and inspection flows.
