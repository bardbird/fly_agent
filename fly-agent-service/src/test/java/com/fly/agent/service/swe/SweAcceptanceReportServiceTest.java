package com.fly.agent.service.swe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweAcceptanceReportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void ensureReportCreatesWorkbookWithSampleAlignedSheets() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath.resolve("patches"));
        Files.createDirectories(packagePath.resolve("docker-image"));
        Files.createDirectories(packagePath.resolve("logs"));
        Files.createDirectories(packagePath.resolve("model_evaluation/opus4.7_pass8_behavior"));
        Files.createDirectories(packagePath.resolve("model_evaluation/qwen3.6_flash_pass4"));
        Files.writeString(packagePath.resolve("task.json"), """
                {
                  "repo": "acme/project",
                  "base_commit": "base",
                  "fix_commit": "fix",
                  "repo_language": "python",
                  "fail_to_pass": ["tests/test_parser.py::test_empty"],
                  "pass_to_pass": ["tests/test_parser.py::test_existing"],
                  "selected_test_files_to_run": ["tests/test_parser.py"],
                  "before_repo_set_cmd": "python -m pip install -e .",
                  "requirements": null,
                  "interface": null,
                  "metadata": {
                    "repo_language": "python",
                    "issue_specificity": ["underspecified"],
                    "issue_categories": ["bug"]
                  }
                }
                """);
        Files.writeString(packagePath.resolve("problem_statement.md"), "Parser crashes on empty input.");
        Files.writeString(packagePath.resolve("requirements.md"), "pytest");
        Files.writeString(packagePath.resolve("interface.md"), "CLI");
        Files.writeString(packagePath.resolve("patches/gold.patch"), """
                diff --git a/src/parser.py b/src/parser.py
                +fixed
                -broken
                """);
        Files.writeString(packagePath.resolve("patches/test.patch"), """
                diff --git a/tests/test_parser.py b/tests/test_parser.py
                +def test_empty(): pass
                """);
        Files.writeString(packagePath.resolve("docker-image/image.tar.sha256"), "abc");
        Files.writeString(packagePath.resolve("logs/fixed.log"), "passed");
        Files.writeString(packagePath.resolve("model_evaluation/opus4.7_pass8_behavior/summary.json"),
                "{\"attempts\":8,\"passes\":1,\"pass_rate\":0.125}");
        Files.writeString(packagePath.resolve("model_evaluation/qwen3.6_flash_pass4/summary.json"),
                "{\"attempts\":4,\"passes\":0,\"pass_rate\":0.0}");

        Path report = new SweAcceptanceReportService().ensureReport(packagePath);

        assertTrue(Files.isRegularFile(report));
        try (ZipFile zip = new ZipFile(report.toFile())) {
            assertNotNull(zip.getEntry("xl/workbook.xml"));
            assertNotNull(zip.getEntry("xl/worksheets/sheet1.xml"));
            assertNotNull(zip.getEntry("xl/worksheets/sheet2.xml"));
            String workbook = new String(zip.getInputStream(zip.getEntry("xl/workbook.xml")).readAllBytes());
            String sheet1 = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).readAllBytes());
            String sheet2 = new String(zip.getInputStream(zip.getEntry("xl/worksheets/sheet2.xml")).readAllBytes());
            assertTrue(workbook.contains("34条验收结果"));
            assertTrue(workbook.contains("汇总"));
            assertTrue(sheet1.contains("序号"));
            assertTrue(sheet1.contains("验收标准内容"));
            assertTrue(sheet1.contains("判断结果"));
            assertTrue(sheet1.contains("修正建议"));
            assertEquals(35, countRows(sheet1));
            assertEquals(11, countRows(sheet2));
        }
    }

    @Test
    void reportNameMatchesAcceptanceTemplate() {
        assertEquals("乙方质检-SWE-Pro数据验收标准对照表.xlsx", SweAcceptanceReportService.REPORT_NAME);
    }

    private int countRows(String sheetXml) {
        int count = 0;
        int index = 0;
        while ((index = sheetXml.indexOf("<row ", index)) >= 0) {
            count++;
            index += 5;
        }
        return count;
    }
}
