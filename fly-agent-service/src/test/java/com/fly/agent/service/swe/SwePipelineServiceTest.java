package com.fly.agent.service.swe;

import com.fly.agent.dao.entity.swe.SweCandidateEntity;
import com.fly.agent.dao.entity.swe.SweTaskEntity;
import com.fly.agent.common.dto.swe.SweModelIoAttemptDTO;
import com.fly.agent.dao.mapper.swe.SweArtifactMapper;
import com.fly.agent.dao.mapper.swe.SweCandidateMapper;
import com.fly.agent.dao.mapper.swe.SwePipelineRunMapper;
import com.fly.agent.dao.mapper.swe.SwePipelineStageMapper;
import com.fly.agent.dao.mapper.swe.SweTaskMapper;
import com.fly.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SwePipelineServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void candidateCsvContainsIssueAndOracleFields() throws Exception {
        SweCandidateMapper candidateMapper = mock(SweCandidateMapper.class);
        SweCandidateEntity candidate = new SweCandidateEntity();
        candidate.setId(12L);
        candidate.setIssueUrl("https://github.com/acme/project/issues/42");
        candidate.setIssueNumbers("[42]");
        candidate.setProblemStatement("Parser crashes on empty input");
        candidate.setHintsText("Maintainer pointed at tokenizer.");
        candidate.setTestPatchPresent(true);
        candidate.setFailToPass("[\"tests/test_parser.py::test_empty\"]");
        candidate.setPassToPass("[]");
        candidate.setBenchmarkStatus("UNKNOWN");
        candidate.setFailedHistoryStatus("UNKNOWN");
        when(candidateMapper.selectById(12L)).thenReturn(candidate);
        when(candidateMapper.selectOne(any())).thenReturn(candidate);

        SwePipelineService service = newService(candidateMapper);
        SweTaskEntity task = new SweTaskEntity();
        task.setCandidateId(12L);
        task.setRepo("acme/project");
        task.setSourceUrl("https://github.com/acme/project/pull/7");
        task.setBaseCommit("base");
        task.setFixCommit("fix");
        task.setRepoLanguage("python");
        task.setIssueSpecificity("[\"api_feat\"]");
        task.setIssueCategories("[\"api_knowledge\",\"back_end_knowledge\"]");

        Method method = SwePipelineService.class.getDeclaredMethod("candidateCsv", SweTaskEntity.class);
        method.setAccessible(true);
        String csv = (String) method.invoke(service, task);

        assertTrue(csv.contains("issue_url"));
        assertTrue(csv.contains("https://github.com/acme/project/issues/42"));
        assertTrue(csv.contains("fail_to_pass"));
        assertTrue(csv.contains("tests/test_parser.py::test_empty"));
        assertTrue(csv.contains("benchmark_status"));
        assertTrue(csv.contains("issue_specificity"));
        assertTrue(csv.contains("[\"\"api_feat\"\"]"));
        assertTrue(csv.contains("api_knowledge"));
    }

    @Test
    void opusEvaluationUsesSweAgentAdapter() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));

        assertFalse(source.contains("generateOpusRetryPrompt"));
        assertFalse(source.contains("gpt_opus_retry_prompt"));
        assertFalse(source.contains("opus4.7_pass8_gpt_retry"));
        assertFalse(source.contains("SwePromptService"));
        assertFalse(source.contains("--prompt-file"));
        assertFalse(source.contains("--llm-align-patches"));
        assertFalse(source.contains("--llm-generate-tests-fallback"));
        assertTrue(source.contains("opus4.7_pass8_swebench_agentic"));
        assertFalse(source.contains("--prompt-mode"));
        assertFalse(source.contains("\"diff\""));
        assertTrue(source.contains("SWE_BENCH_PRO_AGENT_MAX_STEPS = 20"));
        assertTrue(source.contains("--agent-max-steps"));
        assertTrue(source.contains("--agent-max-steps-schedule"));
        assertTrue(source.contains("--max-input-tokens"));
        assertTrue(source.contains("eval_with_swe_agent.py"));
        assertFalse(source.contains("toolkitScript(\"eval_models.py\")"));
    }

    @Test
    void qwenAndOpusUseSameSWEBenchAgenticEvaluatorForFairModelGate() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));
        String devConfig = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../fly-agent-server/src/main/resources/application-dev.yml"));

        assertTrue(source.contains("\"qwen3.6_flash_pass4_swebench_agentic\", runtimeSettingsService.resolveQwenModel(),\n"
                + "                properties.getQwenAttempts(), \"QWEN_API_KEY\", false, true"));
        assertTrue(source.contains("\"opus4.7_pass8_swebench_agentic\", runtimeSettingsService.resolveOpusModel(),\n"
                + "                positiveAttempts(properties.getOpusAttempts(), 8), \"OPUS_API_KEY\", true, true"));
        assertTrue(devConfig.contains("qwen-attempts: 4"));
        assertTrue(devConfig.contains("opus-max-steps-schedule: 180,10"));
    }

    @Test
    void harnessBuildResolvesDeterministicRuntimeEnvironment() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));
        String config = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../docs/CONFIGURATION.md"));

        assertTrue(source.contains("resolve_runtime_env.py"));
        assertTrue(source.contains("resolveRuntimeEnvironment(runId, samplePath)"));
        assertTrue(source.contains("runtime_env.json"));
        assertFalse(source.contains("runRuntimeSetupCommands(runId, packagePath)"));
        assertFalse(source.contains("prepareLocalDependencies(runId, packagePath)"));
        assertTrue(config.contains("runtime_env.json"));
        assertTrue(config.contains("该环境依赖修复路径不接入额外大模型，后续 Qwen/Opus 模型评测仍按流水线执行"));
    }

    @Test
    void qwenEvaluationDisablesThinkingForCostControlWithoutChangingPromptText() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));

        assertTrue(source.contains("command.add(outName.toLowerCase().contains(\"qwen\") ? \"false\" : \"omit\")"));
    }

    @Test
    void modelEvaluationUsesConfiguredOutputBudgetAndTemperature() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));
        String config = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../fly-agent-server/src/main/resources/application.yml"));

        assertTrue(source.contains("command.add(\"--max-tokens\")"));
        assertTrue(source.contains("positiveInteger(model.getMaxTokens(), 4096)"));
        assertTrue(source.contains("command.add(\"--temperature\")"));
        assertTrue(source.contains("model.getTemperature() == null ? 0.7d : model.getTemperature()"));
        assertTrue(config.contains("max-tokens: ${SWE_OPUS_MAX_TOKENS:12000}"));
        assertTrue(config.contains("max-tokens: ${SWE_QWEN_MAX_TOKENS:8192}"));
    }

    @Test
    void resumeRunKeepsGithubPullTasksInProductionMode() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));
        int resumeStart = source.indexOf("public SwePipelineRunDTO resumeRun");
        int launchStart = source.indexOf("launchAfterCommit(task, run.getId(), samplePath, true)", resumeStart);
        String resumeBlock = source.substring(resumeStart, launchStart);

        assertTrue(resumeBlock.contains("String samplePath = isGithubPullTask(task)"));
        assertTrue(resumeBlock.contains("? null"));
        assertTrue(resumeBlock.contains("StringUtils.hasText(request.getSamplePath()) ? request.getSamplePath() : task.getSamplePath()"));
    }

    @Test
    void resumeRunCanReplayFromExplicitStage() throws Exception {
        String dto = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../fly-agent-common/src/main/java/com/fly/agent/common/dto/swe/SwePipelineStartRequest.java"));
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));
        String stageEnum = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../fly-agent-common/src/main/java/com/fly/agent/common/enums/swe/SwePipelineStage.java"));

        assertTrue(dto.contains("private String resumeFromStage"));
        assertTrue(stageEnum.contains("fromCode(String code)"));
        assertTrue(source.contains("resolveResumeFromStage(request.getResumeFromStage())"));
        assertTrue(source.contains("resetStagesFrom(run.getId(), resumeFromStage)"));
        assertTrue(source.contains(".ge(SwePipelineStageEntity::getSortOrder, fromStage.getSortOrder())"));
        assertTrue(source.contains(".set(SwePipelineStageEntity::getStatus, SwePipelineStatus.CREATED.getCode())"));
        assertTrue(source.contains(".set(SwePipelineStageEntity::getResultSummary, null)"));
    }

    @Test
    void inspectedModelSummariesUseSameQwenAndOpusGates() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));

        assertTrue(source.contains("validateInspectedModelSummaryGate(key, json)"));
        assertTrue(source.contains("lower.contains(\"qwen\") && summary.getDoubleValue(\"pass_rate\") > 0.5d"));
        assertTrue(source.contains("lower.contains(\"opus\") && summary.getIntValue(\"passes\") <= 0"));
        assertTrue(source.contains("modelStatusCountsSuffix(json)"));
        assertTrue(source.contains("statusCounts="));
        assertTrue(source.contains("modelStatusCount(summary, \"invalid\") > 0"));
        assertTrue(source.contains("modelStatusCount(summary, \"model_failed\") > 0"));
    }

    @Test
    void runningAndCompletedStagesClearPreviousErrorMessages() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/fly/agent/service/swe/SwePipelineService.java"));

        assertTrue(source.contains("clearRunErrorMessage(runId)"));
        assertTrue(source.contains("clearStageErrorMessage(stageEntity.getId())"));
        assertTrue(source.contains("stageEntity.setErrorMessage(null)"));
        assertTrue(source.contains("set(SwePipelineStageEntity::getErrorMessage, null)"));
        assertTrue(source.contains("set(SwePipelineRunEntity::getErrorMessage, null)"));
    }

    @Test
    void qualityReviewRejectsToolkitPlaceholders() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath.resolve("review"));
        Files.writeString(packagePath.resolve("乙方质检-SWE-Pro数据验收标准对照表.xlsx"), "report");
        Files.writeString(packagePath.resolve("review/reviewer_1.md"),
                "PENDING_REVIEW: 完成真实性与问题陈述核对后填写。");
        Files.writeString(packagePath.resolve("review/reviewer_2.md"), "approved");
        Files.writeString(packagePath.resolve("review/reviewer_3.md"), "approved");
        Files.writeString(packagePath.resolve("review/adjudication_and_calibration.md"), "approved");

        SwePipelineService service = newService(mock(SweCandidateMapper.class));
        Method method = SwePipelineService.class.getDeclaredMethod("inspectQualityEvidence", Long.class, Path.class);
        method.setAccessible(true);

        assertThrows(BusinessException.class, () -> {
            try {
                method.invoke(service, 1L, packagePath);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof BusinessException businessException) {
                    throw businessException;
                }
                throw e;
            }
        });
    }

    @Test
    void verificationEnvUsesPureGoBuildAndReachableGoProxy() throws Exception {
        SwePipelineService service = newService(mock(SweCandidateMapper.class));
        Method method = SwePipelineService.class.getDeclaredMethod("verificationEnv");
        method.setAccessible(true);
        Method fallbackMethod = SwePipelineService.class.getDeclaredMethod("shouldUsePureGoLocalVerification");
        fallbackMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, String> env = (Map<String, String>) method.invoke(service);
        boolean pureGoFallback = (boolean) fallbackMethod.invoke(service);

        if (pureGoFallback) {
            assertTrue("0".equals(env.get("CGO_ENABLED")));
        } else {
            assertFalse(env.containsKey("CGO_ENABLED"));
        }
        assertTrue(env.containsKey("GOPROXY"));
        assertTrue(env.get("GOPROXY").contains("goproxy.cn"));
        assertTrue(env.containsKey("GOSUMDB"));
    }

    @Test
    void modelEvaluationInheritsVerificationEnvironment() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath);
        Path logPath = tempDir.resolve("model_eval.log");
        Files.writeString(logPath, "model eval log");
        SweProperties.Model model = new SweProperties.Model();
        model.setModel("qwen3.6-flash");
        model.setBaseUrl("https://dashscope.example/v1");
        model.setToken("secret-token");

        SweCommandRunner commandRunner = mock(SweCommandRunner.class);
        SweCommandRunner.CommandResult commandResult = new SweCommandRunner.CommandResult();
        commandResult.setExitCode(0);
        commandResult.setLogPath(logPath);
        when(commandRunner.run(any(), any(), any(), any(), any(), any(), eq(false))).thenReturn(commandResult);
        SwePipelineService service = newService(mock(SweCandidateMapper.class), commandRunner);

        Method method = SwePipelineService.class.getDeclaredMethod(
                "runModelEvaluation",
                Long.class,
                Path.class,
                String.class,
                SweProperties.Model.class,
                Integer.class,
                String.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        method.invoke(service, 1L, packagePath, "qwen_demo", model, 1, "QWEN_API_KEY", false, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
        verify(commandRunner).run(
                eq("model_eval_qwen_demo"),
                commandCaptor.capture(),
                eq(packagePath),
                any(),
                envCaptor.capture(),
                any(),
                eq(false));
        List<String> command = commandCaptor.getValue();
        assertTrue(command.contains("--max-input-tokens"));
        assertTrue(command.contains("22000"));
        assertTrue(command.contains("--agent-max-steps"));
        assertTrue(command.contains("20"));
        assertTrue(command.contains("--enable-thinking"));
        assertTrue(command.contains("false"));
        Map<String, String> env = envCaptor.getValue();
        assertTrue("secret-token".equals(env.get("QWEN_API_KEY")));
        assertTrue(env.get("GOPROXY").contains("goproxy.cn"));
        assertTrue(env.containsKey("GOSUMDB"));
    }

    @Test
    void modelIoConsoleReadsSweAgentTrajectoryWhenRawResponseJsonlIsMissing() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Path runDir = packagePath.resolve("model_evaluation/opus_eval/run_01");
        Path trajectoryDir = runDir.resolve("swe_agent/abc123");
        Files.createDirectories(trajectoryDir);
        Files.writeString(runDir.resolve("swe_agent_output.log"), "SWE-agent log without raw jsonl", StandardCharsets.UTF_8);
        Files.writeString(trajectoryDir.resolve("abc123.traj"), """
                {
                  "trajectory": [
                    {
                      "query": [
                        {"role": "system", "content": "system prompt", "message_type": "system_prompt"},
                        {"role": "user", "content": [{"type": "text", "text": "issue text"}], "message_type": "observation"}
                      ],
                      "response": "THOUGHT: inspect\\n\\n```bash\\nls\\n```",
                      "thought": "THOUGHT: inspect",
                      "action": "ls",
                      "execution_time": 1.25
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        SwePipelineService service = newService(mock(SweCandidateMapper.class));
        Method method = SwePipelineService.class.getDeclaredMethod("readModelIoAttempt", Path.class, Path.class);
        method.setAccessible(true);
        SweModelIoAttemptDTO attempt = (SweModelIoAttemptDTO) method.invoke(service, packagePath, runDir);

        assertEquals(1, attempt.getRawResponseLines());
        assertEquals(1, attempt.getResponses().size());
        assertTrue(attempt.getRawResponsePath().endsWith("abc123.traj"));
        assertTrue(attempt.getResponses().get(0).getAssistantContent().contains("THOUGHT: inspect"));
        assertTrue(attempt.getModelInputBlocks().get(0).contains("system prompt"));
        assertTrue(attempt.getModelInputBlocks().get(0).contains("issue text"));
    }

    @Test
    void dockerPackageCleansTempFilesBeforeStaticChecks() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath);
        Path tempFile = packagePath.resolve(".DS_Store");
        Files.writeString(tempFile, "local finder metadata");

        SweCommandRunner commandRunner = mock(SweCommandRunner.class);
        when(commandRunner.run(eq("docker_package"), any(), eq(packagePath.getParent()), any(), any(), any(), eq(false)))
                .thenAnswer(invocation -> {
                    assertFalse(Files.exists(tempFile));
                    throw new BusinessException("stop after preflight cleanup assertion");
                });
        SwePipelineService service = newService(mock(SweCandidateMapper.class), commandRunner);
        Method method = SwePipelineService.class.getDeclaredMethod("runDockerPackage", Long.class, Path.class);
        method.setAccessible(true);

        assertThrows(BusinessException.class, () -> {
            try {
                method.invoke(service, 1L, packagePath);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof BusinessException businessException) {
                    throw businessException;
                }
                throw e;
            }
        });
    }

    @Test
    void packageExportSkipsArchiveCommandWhenValidArchiveAlreadyExists() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath);
        Path archive = tempDir.resolve("production-task-demo-1.tar.gz");
        Files.writeString(archive, "existing delivery archive", StandardCharsets.UTF_8);
        String digest = sha256(archive);
        Files.writeString(tempDir.resolve("production-task-demo-1.tar.gz.sha256"),
                digest + "  production-task-demo-1.tar.gz\n", StandardCharsets.UTF_8);

        SweCommandRunner commandRunner = mock(SweCommandRunner.class);
        SwePipelineService service = newService(mock(SweCandidateMapper.class), commandRunner);
        Method method = SwePipelineService.class.getDeclaredMethod("runPackageExport", Long.class, Path.class);
        method.setAccessible(true);
        method.invoke(service, 1L, packagePath);

        verify(commandRunner, never()).run(eq("package_export"), any(), any(), any(), any(), any(), eq(false));
    }

    @Test
    void datasourceValidatesConnectionsOnBorrowForLongSweStages() throws Exception {
        String config = Files.readString(Path.of("../fly-agent-server/src/main/resources/application.yml"));

        assertTrue(config.contains("validation-query: SELECT 1"));
        assertTrue(config.contains("test-on-borrow: true"));
        assertTrue(config.contains("test-while-idle: true"));
    }

    @Test
    void runWorkspaceUsesAbsoluteProductionRootForToolkitCommands() throws Exception {
        SwePipelineService service = newService(mock(SweCandidateMapper.class));
        Method method = SwePipelineService.class.getDeclaredMethod("runWorkspace", Long.class);
        method.setAccessible(true);

        Path workspace = (Path) method.invoke(service, 42L);

        assertTrue(workspace.isAbsolute());
        assertTrue(workspace.endsWith(Path.of("swe-output", ".swe-runs", "run-42")));
    }

    @Test
    void taskSpecSnapshotRejectsMutationDuringValidationStages() throws Exception {
        Path packagePath = tempDir.resolve("production-task-demo-1");
        Files.createDirectories(packagePath.resolve("patches"));
        Files.createDirectories(packagePath.resolve("scripts"));
        Files.createDirectories(packagePath.resolve("dockerfiles"));
        Files.writeString(packagePath.resolve("task.json"), "{\"repo\":\"acme/demo\"}\n");
        Files.writeString(packagePath.resolve("patches/gold.patch"), "gold\n");
        Files.writeString(packagePath.resolve("patches/test.patch"), "test\n");
        Files.writeString(packagePath.resolve("scripts/run_selected_tests.sh"), "#!/usr/bin/env bash\n");
        Files.writeString(packagePath.resolve("scripts/verify_patch_application.sh"), "#!/usr/bin/env bash\n");
        Files.writeString(packagePath.resolve("dockerfiles/Dockerfile"), "FROM ubuntu:22.04\n");

        SwePipelineService service = newService(mock(SweCandidateMapper.class));
        Method snapshotMethod = SwePipelineService.class.getDeclaredMethod("taskSpecSnapshot", Path.class);
        snapshotMethod.setAccessible(true);
        Object snapshot = snapshotMethod.invoke(service, packagePath);

        Files.writeString(packagePath.resolve("task.json"), "{\"repo\":\"acme/changed\"}\n");

        Method assertMethod = SwePipelineService.class.getDeclaredMethod(
                "assertTaskSpecUnchanged", Path.class, snapshot.getClass(), String.class);
        assertMethod.setAccessible(true);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> assertMethod.invoke(service, packagePath, snapshot, "unit test"));
        assertTrue(error.getCause() instanceof BusinessException);
        assertTrue(error.getCause().getMessage().contains("task spec changed"));
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }

    private SwePipelineService newService(SweCandidateMapper candidateMapper) {
        return newService(candidateMapper, mock(SweCommandRunner.class));
    }

    private SwePipelineService newService(SweCandidateMapper candidateMapper, SweCommandRunner commandRunner) {
        SweProperties properties = new SweProperties();
        SweRuntimeSettingsService runtimeSettingsService = mock(SweRuntimeSettingsService.class);
        when(runtimeSettingsService.resolveQwenModel()).thenReturn(properties.getQwen());
        when(runtimeSettingsService.resolveOpusModel()).thenReturn(properties.getOpus());
        when(runtimeSettingsService.resolveGithubToken(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new SwePipelineService(
                mock(SweTaskMapper.class),
                mock(SwePipelineRunMapper.class),
                mock(SwePipelineStageMapper.class),
                mock(SweArtifactMapper.class),
                candidateMapper,
                properties,
                runtimeSettingsService,
                commandRunner,
                mock(SweAcceptanceReportService.class));
    }
}
