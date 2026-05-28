# Task-Level SCA Analysis Design

Date: 2026-05-26

## Goal

Add a task-level SCA generation step to the SWE-Pro production pipeline. The step must produce delivery files required by `~/Downloads/swe-qa/SCA文件准备清单.xlsx` for each task package, using real scanner output only. If a field cannot be obtained from scanner output, `task.json`, or repository files, it must be marked `待复核`; it must not be guessed.

This design covers task-level SCA output. Batch-level reports can later be aggregated from task SCA directories and are out of scope for this change.

## Pipeline Placement

Add a new pipeline stage:

`TASK_SCA_ANALYSIS`, sort order `35`, between `TASK_PACKAGE_INIT` and `PATCH_VERIFY`.

Rationale:

- `TASK_PACKAGE_INIT` creates `task.json`, `repo/`, `patches/`, and baseline metadata.
- SCA should scan the task's repository snapshot before test execution, model evaluation, Docker packaging, or export.
- Early failure catches missing scanner setup before long-running stages.
- A dedicated stage keeps logs, resume behavior, and artifacts easy to inspect.

Both production runs and existing-package inspection runs will include this stage:

- Production mode runs the SCA generator and records the generated files.
- Existing-package mode validates and indexes an existing SCA delivery directory.

## Generated Files

The task package will contain:

```text
SCA_交付材料/
  01_task_SCA报告.md
  02_数据级SCA明细表.csv
  03_开源组件与许可证清单.csv
  04_SBOM文件/
    <task_id>_sbom.spdx.json
  05_原始扫描日志/
    <task_id>_sca_scan.json
    <task_id>_sca_scan.log
  06_LICENSE_NOTICE归档/
    <task_id>_LICENSE.txt
    <task_id>_NOTICE.txt
  07_风险数据清单.csv
```

CSV is used for deterministic generation and easy testing. The column names will match the Excel checklist exactly for the task-level sheets.

If a repository has no `NOTICE` file, the NOTICE archive file is omitted and the detail CSV records `待复核` or `否` only when the scanner or files make that clear. If no `LICENSE` file is found, the LICENSE archive file is omitted and the report marks license path and license conclusion as `待复核`.

## Scanner Rules

The generator will call a real scanner executable:

1. Prefer `syft`, generating SPDX JSON for `repo/`.
2. If `syft` is unavailable, use `trivy` if installed and request SBOM JSON output.
3. If neither scanner is available, fail the SCA stage. Do not generate synthetic SBOM or component data.

The raw scanner output is saved unchanged under `05_原始扫描日志/`. The normalized CSV and Markdown report are derived from that raw output plus local package files.

The generator may use file-system discovery for evidence that does not require a scanner:

- `task.json` fields: task id, repo, source URL, base commit, language.
- `repo/LICENSE*`, `repo/COPYING*`, `repo/NOTICE*`.
- dependency manifest file paths such as `requirements.txt`, `package-lock.json`, `go.mod`, `pom.xml`, `Cargo.lock`, `composer.lock`, `Package.swift`, `build.gradle`, and similar.

It must not infer unknown license terms, commercial-use rights, AI-training restrictions, or component licenses without scanner evidence or explicit files.

## Field Semantics

The data-level CSV will include all required fields from checklist section `02、数据级 SCA 明细表`:

- Directly known fields are filled from `task.json`, scanner metadata, or repository files.
- Component counts and dependency counts are computed from scanner packages when available.
- `是否生成 SBOM` is `是` only when a scanner successfully writes the SBOM file.
- `是否存在未知许可证` is `是` if scanner components include missing, unknown, or `NOASSERTION` licenses; otherwise `否` when scanner license data is complete.
- High-risk/copyleft findings are based on SPDX IDs observed in scanner output.
- `是否存在商业使用限制`, `是否存在 AI 训练限制`, and similar legal-interpretation fields are `待复核` unless the scanner output or license text provides a direct, machine-checkable signal.
- `商业 AI 训练兼容性结论` is:
  - `不建议使用` when high-risk/copyleft or explicitly restrictive licenses are detected.
  - `需复核` when any unknown license, missing license, or legal-interpretation field remains unresolved.
  - `兼容` only when all detected licenses are in the existing compatible allowlist and no required field is unresolved.

The component CSV will include all fields from checklist section `03、开源组件 / 许可证清单`. Missing per-component values become `待复核`, not fabricated defaults.

The risk CSV will include one row per unresolved or risky finding, including:

- Missing scanner.
- Missing or unknown repo license.
- Missing component license.
- High-risk/copyleft SPDX ID.
- Commercial-use or AI-training restriction requiring manual review.

## Java Integration

`SwePipelineService` will add methods equivalent to:

- `runTaskScaAnalysis(runId, packagePath)`: runs the Python generator, records its log, then validates and indexes SCA files.
- `inspectTaskScaEvidence(runId, packagePath)`: validates existing SCA output and records artifacts.

Artifacts will use a new artifact type such as `TASK_SCA`.

`inspectPackageExport` will continue to include delivery files through the normal archive path, and will also index SCA files for visibility.

The Python script will live under:

`tools/swe-pro-production/scripts/generate_task_sca.py`

The Java stage will call it using `properties.getPython()` so it follows the existing toolkit invocation pattern.

## Error Handling

The SCA stage fails when:

- `task.json` or `repo/` is missing.
- No supported scanner is available.
- The scanner exits non-zero.
- Scanner output cannot be parsed enough to prove that a real scan ran.
- Required SCA directory or required generated files are missing after generation.

The generator still writes a scanner log before failing when possible. It does not write a successful report from incomplete or missing scanner output.

Unobtainable checklist fields are not errors if a real scan completed; they are marked `待复核` and listed in `07_风险数据清单.csv`.

## Testing

Tests will be added before implementation:

- Python unit tests for scanner output normalization:
  - Parses Syft-like SPDX JSON into component rows.
  - Marks unknown licenses as `待复核` and emits risk rows.
  - Fails when no scanner executable is available.
  - Copies LICENSE/NOTICE files only when present.
- Java service tests:
  - Pipeline order includes `TASK_SCA_ANALYSIS` after package init and before patch verify.
  - Existing-package inspection requires and records SCA evidence.
  - Production-mode stage invokes the SCA generator before local verification.

Because the current local Maven is running Java 8 while the project targets Java 17, Java test execution requires switching `JAVA_HOME` to JDK 17.

## Non-Goals

- No batch-level SCA summary generation in this change.
- No legal opinion generation.
- No invented license compatibility data.
- No automatic scanner installation.
- No database schema changes for task-level SCA outputs.
