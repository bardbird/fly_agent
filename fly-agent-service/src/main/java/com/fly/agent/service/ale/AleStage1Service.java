package com.fly.agent.service.ale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.common.dto.ale.AleOptionsResponse;
import com.fly.agent.common.dto.ale.AleOptionDTO;
import com.fly.agent.common.dto.ale.AleRunDTO;
import com.fly.agent.common.dto.ale.AleRunRequest;
import com.fly.agent.common.dto.ale.AleRunSummaryDTO;
import com.fly.agent.common.dto.ale.AleTaskDTO;
import com.fly.agent.common.enums.ale.AleRunStatus;
import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.entity.ale.AleTaskEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AleStage1Service {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final String DEFAULT_MODEL = "gpt-5";
    private static final int DEFAULT_TARGET_COUNT = 8;
    private static final String SKILL_ROOT = "codex-skills/ale-task-factory";

    private final AleRunMapper runMapper;
    private final AleTaskMapper taskMapper;
    private final AleProperties properties;

    public AleOptionsResponse getOptions() {
        AleOptionsResponse response = new AleOptionsResponse();
        response.setDomains(options(
                "general-workflows", "通用工作流",
                "documentation", "文档生成",
                "data-processing", "数据处理",
                "code-analysis", "代码分析",
                "research-synthesis", "研究综述"));
        response.setDisciplines(options(
                "software-engineering", "软件工程",
                "data-science", "数据科学",
                "operations", "运营分析",
                "finance", "金融分析",
                "security", "安全分析"));
        response.setScenarios(options(
                "task-authoring", "题目生成",
                "batch-verification", "批量验证",
                "failure-repair", "失败复盘",
                "quality-screening", "质量筛选"));
        response.setDifficulties(options(
                "easy", "Easy",
                "medium", "Medium",
                "hard", "Hard"));
        response.setInputModes(options(
                "brief", "Brief 文本",
                "structured-plan", "结构化计划",
                "seed-task", "种子任务",
                "dataset", "数据集"));
        response.setOutputModes(options(
                "task-package", "Task Package",
                "task-list", "Task List",
                "report", "Report",
                "review-artifacts", "Review Artifacts"));
        response.setVerificationModes(options(
                "oracle", "Oracle",
                "fixture", "Fixture",
                "rules", "Rules",
                "human-reviewed", "Human Reviewed"));
        response.setReferenceStrategies(options(
                "hidden-reference", "Hidden Reference",
                "public-fixture", "Public Fixture",
                "generated-reference", "Generated Reference"));
        response.setCodexModels(options(
                DEFAULT_MODEL, DEFAULT_MODEL,
                "gpt-5-mini", "gpt-5-mini",
                "gpt-5-codex", "gpt-5-codex"));
        return response;
    }

    public AleRunDTO startRun(AleRunRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String runKey = buildRunKey(request);
        Path runDir = runDirectory(runKey);
        try {
            Files.createDirectories(runDir);
        } catch (IOException e) {
            throw new IllegalStateException("failed to create ALE run directory", e);
        }

        AleRunEntity run = new AleRunEntity();
        run.setRunKey(runKey);
        run.setDomain(request.getDomain());
        run.setDiscipline(request.getDiscipline());
        run.setScenario(request.getScenario());
        run.setDifficulty(request.getDifficulty());
        run.setInputMode(request.getInputMode());
        run.setOutputMode(request.getOutputMode());
        run.setVerificationMode(request.getVerificationMode());
        run.setReferenceStrategy(request.getReferenceStrategy());
        run.setTargetCount(Math.max(request.getTargetCount(), DEFAULT_TARGET_COUNT));
        run.setCodexModel(request.getCodexModel());
        run.setStatus(AleRunStatus.CREATED.name());
        run.setProgressPercent(0);
        run.setTotalTasks(0);
        run.setCompletedTasks(0);
        run.setFailedTasks(0);
        run.setBlockedTasks(0);
        run.setOutputRoot(runDir.toString());
        run.setLogPath(runDir.resolve("codex.log").toString());
        run.setSummaryPath(runDir.resolve("summary.json").toString());
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        runMapper.insert(run);

        EXECUTOR.submit(() -> executeRun(run.getId(), request, runDir));
        return getRun(run.getId());
    }

    public List<AleRunDTO> listRuns() {
        List<AleRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<AleRunEntity>().orderByDesc(AleRunEntity::getCreatedAt));
        return runs.stream().map(this::toRunDTO).collect(Collectors.toList());
    }

    public List<AleRunSummaryDTO> listSummaries() {
        List<AleRunEntity> runs = runMapper.selectList(new LambdaQueryWrapper<AleRunEntity>().orderByDesc(AleRunEntity::getCreatedAt));
        return runs.stream().map(this::toSummaryDTO).collect(Collectors.toList());
    }

    public AleRunDTO getRun(Long id) {
        AleRunEntity run = runMapper.selectById(id);
        if (run == null) {
            return null;
        }
        return toRunDTO(run);
    }

    public List<AleTaskDTO> listTasks(Long runId) {
        List<AleTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<AleTaskEntity>()
                .eq(AleTaskEntity::getRunId, runId)
                .orderByAsc(AleTaskEntity::getId));
        return tasks.stream().map(this::toTaskDTO).collect(Collectors.toList());
    }

    public List<String> tailLog(Long runId, int lines) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null || !StringUtils.hasText(run.getLogPath())) {
            return List.of();
        }
        Path logPath = Path.of(run.getLogPath());
        if (!Files.exists(logPath)) {
            return List.of();
        }
        try {
            List<String> all = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(lines, 1));
            return all.subList(from, all.size());
        } catch (IOException e) {
            return List.of("failed to read log: " + e.getMessage());
        }
    }

    private void executeRun(Long runId, AleRunRequest request, Path runDir) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        try {
            updateRun(runId, AleRunStatus.RUNNING, 10, null);
            Path requestPath = runDir.resolve("request.json");
            Path planPath = runDir.resolve("plan.json");
            Files.writeString(requestPath, toJson(request), StandardCharsets.UTF_8);
            Files.writeString(planPath, buildPlanJson(request, runDir), StandardCharsets.UTF_8);

            List<AleTaskEntity> tasks = createTasks(runId, request);
            updateTaskSummary(runId, tasks);

            Path logPath = runDir.resolve("codex.log");
            List<String> command = buildCommand(planPath, runDir);
            int exitCode = runCodex(command, Path.of(".").toAbsolutePath().normalize(), logPath, runDir);
            if (exitCode == 0) {
                markTasksFinished(runId, "COMPLETED", null);
                writeSummary(runDir, runId, request, tasks, "COMPLETED", null);
                updateRun(runId, AleRunStatus.COMPLETED, 100, null);
            } else {
                String message = "codex exit code " + exitCode;
                markTasksFinished(runId, "FAILED", message);
                writeSummary(runDir, runId, request, tasks, "FAILED", message);
                updateRun(runId, AleRunStatus.FAILED, 100, message);
            }
        } catch (Exception e) {
            log.error("ALE stage1 run failed", e);
            try {
                writeSummary(runDir, runId, request, List.of(), "FAILED", e.getMessage());
            } catch (IOException ioException) {
                log.error("failed to write ALE stage1 summary", ioException);
            }
            updateRun(runId, AleRunStatus.FAILED, 100, e.getMessage());
        }
    }

    private List<AleTaskEntity> createTasks(Long runId, AleRunRequest request) {
        List<AleTaskEntity> tasks = new ArrayList<>();
        int count = Math.max(request.getTargetCount(), DEFAULT_TARGET_COUNT);
        for (int i = 1; i <= count; i++) {
            AleTaskEntity task = new AleTaskEntity();
            task.setRunId(runId);
            task.setTaskId(String.format("%s-%s-%02d", request.getDomain(), request.getScenario(), i));
            task.setTitle(request.getScenario() + " #" + i);
            task.setDomain(request.getDomain());
            task.setDiscipline(request.getDiscipline());
            task.setScenario(request.getScenario());
            task.setDifficulty(request.getDifficulty());
            task.setStatus("CREATED");
            taskMapper.insert(task);
            tasks.add(task);
        }
        return tasks;
    }

    private void updateTaskSummary(Long runId, List<AleTaskEntity> tasks) {
        AleRunEntity run = new AleRunEntity();
        run.setId(runId);
        run.setTotalTasks(tasks.size());
        run.setCompletedTasks(0);
        run.setFailedTasks(0);
        run.setBlockedTasks(0);
        run.setProgressPercent(15);
        runMapper.updateById(run);
    }

    private void markTasksFinished(Long runId, String status, String errorMessage) {
        List<AleTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<AleTaskEntity>()
                .eq(AleTaskEntity::getRunId, runId));
        for (AleTaskEntity task : tasks) {
            AleTaskEntity update = new AleTaskEntity();
            update.setId(task.getId());
            update.setStatus(status);
            update.setErrorMessage(errorMessage);
            taskMapper.updateById(update);
        }
        AleRunEntity run = new AleRunEntity();
        run.setId(runId);
        run.setCompletedTasks("COMPLETED".equals(status) ? tasks.size() : 0);
        run.setFailedTasks("FAILED".equals(status) ? tasks.size() : 0);
        run.setProgressPercent(100);
        runMapper.updateById(run);
    }

    private void updateRun(Long runId, AleRunStatus status, int progress, String errorMessage) {
        AleRunEntity update = new AleRunEntity();
        update.setId(runId);
        update.setStatus(status.name());
        update.setProgressPercent(progress);
        update.setErrorMessage(errorMessage);
        if (status == AleRunStatus.RUNNING) {
            update.setStartedAt(LocalDateTime.now());
        } else if (status == AleRunStatus.COMPLETED || status == AleRunStatus.FAILED || status == AleRunStatus.BLOCKED) {
            update.setFinishedAt(LocalDateTime.now());
        }
        update.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(update);
    }

    private List<String> buildCommand(Path planPath, Path runDir) {
        List<String> command = new ArrayList<>();
        command.add(properties.getCodexBinary());
        command.add("exec");
        command.add("-C");
        command.add(Path.of(".").toAbsolutePath().normalize().toString());
        command.add("--sandbox");
        command.add("danger-full-access");
        command.add("--ask-for-approval");
        command.add("never");
        command.add("--model");
        command.add(DEFAULT_MODEL);
        command.add("Use the ALE task factory skill to generate the run described in " + planPath.toAbsolutePath() + ". Write logs and artifacts under " + runDir.toAbsolutePath() + ".");
        return command;
    }

    private int runCodex(List<String> command, Path cwd, Path logPath, Path runDir) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        Map<String, String> env = builder.environment();
        env.put("ALE_OUTPUT_ROOT", runDir.toAbsolutePath().toString());
        env.put("ALE_STAGE1", "true");
        Process process = builder.start();
        int exit = process.waitFor();
        Files.writeString(logPath, System.lineSeparator() + "exitCode=" + exit + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        return exit;
    }

    private void writeSummary(Path runDir, Long runId, AleRunRequest request, List<AleTaskEntity> tasks, String status, String errorMessage) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("runKey", runDir.getFileName().toString());
        summary.put("status", status);
        summary.put("domain", request.getDomain());
        summary.put("discipline", request.getDiscipline());
        summary.put("scenario", request.getScenario());
        summary.put("difficulty", request.getDifficulty());
        summary.put("targetCount", request.getTargetCount());
        summary.put("generatedCount", tasks.size());
        summary.put("taskIds", tasks.stream().map(AleTaskEntity::getTaskId).toList());
        summary.put("errorMessage", errorMessage);
        Files.writeString(runDir.resolve("summary.json"), toJson(summary), StandardCharsets.UTF_8);
    }

    private AleRunDTO toRunDTO(AleRunEntity run) {
        AleRunDTO dto = new AleRunDTO();
        BeanUtils.copyProperties(run, dto);
        dto.setTasks(listTasks(run.getId()));
        dto.setDomainStats(domainStats());
        return dto;
    }

    private AleRunSummaryDTO toSummaryDTO(AleRunEntity run) {
        AleRunSummaryDTO dto = new AleRunSummaryDTO();
        BeanUtils.copyProperties(run, dto);
        dto.setRunId(run.getId());
        dto.setDomainStats(domainStats());
        return dto;
    }

    private AleTaskDTO toTaskDTO(AleTaskEntity entity) {
        AleTaskDTO dto = new AleTaskDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    private Map<String, Long> domainStats() {
        List<AleRunEntity> runs = runMapper.selectList(null);
        return runs.stream().collect(Collectors.groupingBy(AleRunEntity::getDomain, LinkedHashMap::new, Collectors.counting()));
    }

    private List<AleOptionDTO> options(String... pairs) {
        List<AleOptionDTO> result = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result.add(new AleOptionDTO(pairs[i], pairs[i + 1]));
        }
        return result;
    }

    private String buildRunKey(AleRunRequest request) {
        return request.getDomain() + "__" + request.getScenario() + "__" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Path runDirectory(String runKey) {
        return Path.of(properties.getOutputRoot()).resolve(runKey);
    }

    private String toJson(Object value) {
        return com.alibaba.fastjson2.JSON.toJSONString(value, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
    }

    private String buildPlanJson(AleRunRequest request, Path runDir) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("runKey", runDir.getFileName().toString());
        plan.put("request", request);
        plan.put("outputRoot", runDir.toAbsolutePath().toString());
        plan.put("skillRoot", SKILL_ROOT);
        plan.put("steps", List.of("brief", "draft", "scaffold", "oracle_validate"));
        return toJson(plan);
    }
}
