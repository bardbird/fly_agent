package com.fly.agent.service.swe;

import com.fly.agent.common.dto.swe.SweScaReportGenerateRequest;
import com.fly.agent.common.dto.swe.SweScaReportGenerateResponse;
import com.fly.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SweScaReportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void generateInvokesPythonScriptAndReturnsDeliveryFiles() throws Exception {
        Path toolkitRoot = tempDir.resolve("tools/swe-pro-production");
        Files.createDirectories(toolkitRoot.resolve("scripts"));
        Files.writeString(toolkitRoot.resolve("scripts/generate_task_sca.py"), "# generator\n");
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath.resolve("repo"));
        Files.writeString(packagePath.resolve("task.json"), "{\"task_id\":\"production-task-demo-1\"}");
        Path delivery = packagePath.resolve("SCA_交付材料");
        Files.createDirectories(delivery.resolve("04_SBOM文件"));
        Files.createDirectories(delivery.resolve("05_原始扫描日志"));
        Files.createDirectories(delivery.resolve("06_LICENSE_NOTICE归档"));
        Files.writeString(delivery.resolve("01_task_SCA报告.md"), "# report\n", StandardCharsets.UTF_8);
        Files.writeString(delivery.resolve("02_数据级SCA明细表.csv"), "a,b\n", StandardCharsets.UTF_8);
        Files.writeString(delivery.resolve("03_开源组件与许可证清单.csv"), "a,b\n", StandardCharsets.UTF_8);
        Files.writeString(delivery.resolve("04_SBOM文件/production-task-demo-1_sbom.spdx.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(delivery.resolve("05_原始扫描日志/production-task-demo-1_sca_scan.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(delivery.resolve("05_原始扫描日志/production-task-demo-1_sca_scan.log"), "ok", StandardCharsets.UTF_8);
        Files.writeString(delivery.resolve("07_风险数据清单.csv"), "a,b\n", StandardCharsets.UTF_8);

        SweCommandRunner commandRunner = mock(SweCommandRunner.class);
        SweCommandRunner.CommandResult commandResult = new SweCommandRunner.CommandResult();
        commandResult.setExitCode(0);
        commandResult.setLogPath(tempDir.resolve("sca.log"));
        commandResult.setOutput("generated");
        when(commandRunner.run(any(), any(), any(), any(), any(), any(), eq(false))).thenReturn(commandResult);

        SweProperties properties = new SweProperties();
        properties.setToolkitRoot(toolkitRoot.toString());
        properties.setProductionRoot(tempDir.resolve("swe-output").toString());
        properties.setPython("python3");
        SweScaReportService service = new SweScaReportService(properties, commandRunner);
        SweScaReportGenerateRequest request = new SweScaReportGenerateRequest();
        request.setPackagePath(packagePath.toString());

        SweScaReportGenerateResponse response = service.generate(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verifyCommand(commandRunner, commandCaptor, packagePath);
        assertEquals(delivery.toAbsolutePath().normalize().toString(), response.getOutputDir());
        assertTrue(response.getGeneratedFiles().stream().anyMatch(path -> path.endsWith("01_task_SCA报告.md")));
        assertEquals("generated", response.getSummary());
    }

    @Test
    void generateSupportsManifestOnlyOutputDirectoryForApiRuns() throws Exception {
        Path toolkitRoot = tempDir.resolve("tools/swe-pro-production");
        Files.createDirectories(toolkitRoot.resolve("scripts"));
        Files.writeString(toolkitRoot.resolve("scripts/generate_task_sca.py"), "# generator\n");
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath.resolve("repo"));
        Files.writeString(packagePath.resolve("task.json"), "{\"task_id\":\"production-task-demo-1\"}");
        Path outputDir = tempDir.resolve("generated-sca");
        Files.createDirectories(outputDir.resolve("04_SBOM文件"));
        Files.createDirectories(outputDir.resolve("05_原始扫描日志"));
        Files.writeString(outputDir.resolve("01_task_SCA报告.md"), "# report\n", StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("02_数据级SCA明细表.csv"), "a,b\n", StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("03_开源组件与许可证清单.csv"), "a,b\n", StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("04_SBOM文件/production-task-demo-1_sbom.spdx.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("05_原始扫描日志/production-task-demo-1_sca_scan.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("07_风险数据清单.csv"), "a,b\n", StandardCharsets.UTF_8);

        SweCommandRunner commandRunner = mock(SweCommandRunner.class);
        SweCommandRunner.CommandResult commandResult = new SweCommandRunner.CommandResult();
        commandResult.setExitCode(0);
        commandResult.setLogPath(tempDir.resolve("sca.log"));
        commandResult.setOutput("generated");
        when(commandRunner.run(any(), any(), any(), any(), any(), any(), eq(false))).thenReturn(commandResult);

        SweProperties properties = new SweProperties();
        properties.setToolkitRoot(toolkitRoot.toString());
        properties.setPython("python3");
        SweScaReportService service = new SweScaReportService(properties, commandRunner);
        SweScaReportGenerateRequest request = new SweScaReportGenerateRequest();
        request.setPackagePath(packagePath.toString());
        request.setOutputDir(outputDir.toString());
        request.setManifestOnly(true);

        SweScaReportGenerateResponse response = service.generate(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        verify(commandRunner).run(
                eq("generate_task_sca"),
                commandCaptor.capture(),
                any(),
                any(),
                eq(Map.of()),
                eq(Duration.ofMinutes(10)),
                eq(false));
        List<String> command = commandCaptor.getValue();
        assertTrue(command.contains("--manifest-only"));
        assertTrue(command.contains("--output-dir"));
        assertTrue(command.contains(outputDir.toAbsolutePath().normalize().toString()));
        assertEquals(outputDir.toAbsolutePath().normalize().toString(), response.getOutputDir());
    }

    @Test
    void generateRejectsPackageWithoutTaskJson() {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        packagePath.toFile().mkdirs();
        SweScaReportService service = new SweScaReportService(new SweProperties(), mock(SweCommandRunner.class));
        SweScaReportGenerateRequest request = new SweScaReportGenerateRequest();
        request.setPackagePath(packagePath.toString());

        assertThrows(BusinessException.class, () -> service.generate(request));
    }

    @Test
    void sweControllerExposesScaReportGenerateEndpoint() throws Exception {
        String source = Files.readString(Path.of(
                "../fly-agent-server/src/main/java/com/fly/agent/api/controller/swe/SwePipelineController.java"));

        assertTrue(source.contains("SweScaReportService"));
        assertTrue(source.contains("@PostMapping(\"/sca-report/generate\")"));
        assertTrue(source.contains("SweScaReportGenerateRequest"));
        assertTrue(source.contains("sweScaReportService.generate(request)"));
    }

    private void verifyCommand(
            SweCommandRunner commandRunner,
            ArgumentCaptor<List<String>> commandCaptor,
            Path packagePath) {
        verify(commandRunner).run(
                eq("generate_task_sca"),
                commandCaptor.capture(),
                any(),
                any(),
                eq(Map.of()),
                eq(Duration.ofMinutes(10)),
                eq(false));
        List<String> command = commandCaptor.getValue();
        assertEquals("python3", command.get(0));
        assertTrue(command.get(1).endsWith("generate_task_sca.py"));
        assertTrue(command.contains("--package"));
        assertTrue(command.contains(packagePath.toAbsolutePath().normalize().toString()));
    }
}
