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
    private static final int DEFAULT_TARGET_COUNT = 1;

    private final AleRunMapper runMapper;
    private final AleTaskMapper taskMapper;
    private final AleExecutionGateway gateway;
    private final AleProperties properties;

    public AleOptionsResponse getOptions() {
        AleOptionsResponse response = new AleOptionsResponse();
        response.setDomains(options(
                "agriculture_env", "Agriculture Env",
                "business_finance", "Business Finance",
                "computing_math", "Computing Math",
                "education_info", "Education Info",
                "engineering", "Engineering",
                "health_medicine", "Health Medicine",
                "legal", "Legal",
                "life_sciences", "Life Sciences",
                "physical_sciences", "Physical Sciences",
                "psychology_neuro", "Psychology Neuro",
                "social_sciences", "Social Sciences",
                "transport_safety", "Transport Safety",
                "visual_media", "Visual Media",
                "other", "Other"));
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
        response.setCodexModels(codexModelOptions());
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
        run.setTargetCount(resolveTargetCount(request));
        run.setCodexModel(request.getCodexModel());
        run.setStatus(AleRunStatus.CREATED.name());
        run.setProgressPercent(0);
        run.setTotalTasks(0);
        run.setCompletedTasks(0);
        run.setFailedTasks(0);
        run.setBlockedTasks(0);
        run.setOutputRoot(runDir.toString());
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
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            return List.of();
        }
        return gateway.tailLog(Path.of(run.getOutputRoot()), "stage1", lines);
    }

    private void executeRun(Long runId, AleRunRequest request, Path runDir) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }
        try {
            updateRun(runId, AleRunStatus.RUNNING, 10, null);
            Files.writeString(runDir.resolve("request.json"), toJson(request));

            List<AleTaskEntity> tasks = createTasks(runId, request);
            updateTaskSummary(runId, tasks);
            markTasksStatus(runId, "RUNNING", null);

            List<Map<String, String>> taskContract = tasks.stream()
                    .map(t -> Map.of("task_id", t.getTaskId(), "title",
                            t.getTitle() == null ? "" : t.getTitle()))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "stage1");
            payload.put("run_id", runId);
            payload.put("run_dir", runDir.toString());
            Map<String, Object> stage1 = new LinkedHashMap<>();
            stage1.put("framework_root", frameworkRoot().toString());
            stage1.put("codex_model", request.getCodexModel());
            stage1.put("tasks", taskContract);
            stage1.put("request", toJsonMap(request));
            payload.put("stage1", stage1);

            AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                    runId, runDir, payload, percent -> updateProgressPercent(runId, percent));

            List<OracleTaskResult> oracleResults = parseOracleResults(runDir, tasks);
            applyOracleResults(runId, oracleResults);
            updateTaskCounts(runId, oracleResults);

            if (result.isDone() && !hasFailedTasks(oracleResults)) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "COMPLETED", null);
                updateRun(runId, AleRunStatus.COMPLETED, 100, null);
            } else if (result.isDone() && allTasksBlocked(oracleResults)) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "BLOCKED", "all tasks blocked");
                updateRun(runId, AleRunStatus.BLOCKED, 100, "all tasks blocked");
            } else if (result.isDone()) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "COMPLETED", "some tasks failed/blocked");
                updateRun(runId, AleRunStatus.COMPLETED, 100, "some tasks failed/blocked");
            } else {
                markTasksStatus(runId, "FAILED", result.message());
                writeSummaryIfMissing(runDir, runId, request, tasks, "FAILED", result.message());
                updateRun(runId, AleRunStatus.FAILED, 100, result.message());
            }
        } catch (Exception e) {
            log.error("ALE stage1 run failed", e);
            markTasksStatus(runId, "FAILED", e.getMessage());
            updateTaskCountsToFailed(runId);
            try {
                writeSummaryIfMissing(runDir, runId, request, List.of(), "FAILED", e.getMessage());
            } catch (IOException ioException) {
                log.error("failed to write ALE stage1 summary", ioException);
            }
            updateRun(runId, AleRunStatus.FAILED, 100, e.getMessage());
        }
    }

    private void updateProgressPercent(Long runId, int percent) {
        AleRunEntity u = new AleRunEntity();
        u.setId(runId);
        u.setProgressPercent(percent);
        runMapper.updateById(u);
    }

    private Map<String, Object> toJsonMap(AleRunRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", r.getDomain());
        m.put("discipline", r.getDiscipline());
        m.put("scenario", r.getScenario());
        m.put("difficulty", r.getDifficulty());
        m.put("input_mode", r.getInputMode());
        m.put("output_mode", r.getOutputMode());
        m.put("verification_mode", r.getVerificationMode());
        m.put("reference_strategy", r.getReferenceStrategy());
        m.put("target_count", r.getTargetCount());
        m.put("codex_model", r.getCodexModel());
        return m;
    }

    private List<AleTaskEntity> createTasks(Long runId, AleRunRequest request) {
        List<AleTaskEntity> tasks = new ArrayList<>();
        int count = resolveTargetCount(request);
        for (int i = 1; i <= count; i++) {
            AleTaskEntity task = new AleTaskEntity();
            task.setRunId(runId);
            String taskName = String.format("%s_%02d", request.getScenario().replace('-', '_'), i);
            task.setTaskId(request.getDomain() + "/" + taskName);
            task.setTitle(request.getScenario() + " #" + i);
            task.setDomain(request.getDomain());
            task.setDiscipline(request.getDiscipline());
            task.setScenario(request.getScenario());
            task.setDifficulty(request.getDifficulty());
            task.setStatus("CREATED");
            task.setTaskDir(runDirectoryPlaceholder(request.getDomain(), taskName));
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
        run.setProgressPercent(20);
        runMapper.updateById(run);
    }

    private void markTasksStatus(Long runId, String status, String errorMessage) {
        List<AleTaskEntity> tasks = taskMapper.selectList(new LambdaQueryWrapper<AleTaskEntity>()
                .eq(AleTaskEntity::getRunId, runId));
        for (AleTaskEntity task : tasks) {
            AleTaskEntity update = new AleTaskEntity();
            update.setId(task.getId());
            update.setStatus(status);
            update.setErrorMessage(errorMessage);
            taskMapper.updateById(update);
        }
    }

    // ── oracle-aware per-task status ──────────────────────────────────────────────

    private record OracleTaskResult(String taskId, String status, Double oracleScore,
                                     String evidencePath, boolean dryRunOk,
                                     boolean taskLoaderOk, boolean evidenceOk,
                                     String blockedReason) {}

    private void markTaskStatus(Long taskId, String status, Double oracleScore,
                                 String evidencePath, String errorMessage) {
        AleTaskEntity update = new AleTaskEntity();
        update.setId(taskId);
        update.setStatus(status);
        update.setScore(oracleScore != null ? java.math.BigDecimal.valueOf(oracleScore) : null);
        update.setEvidencePath(evidencePath);
        update.setErrorMessage(errorMessage);
        taskMapper.updateById(update);
    }

    /**
     * Apply per-task oracle results from summary.json.
     * Falls back to uniform status if summary.json doesn't contain oracle data.
     */
    private void applyOracleResults(Long runId, List<OracleTaskResult> results) {
        List<AleTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AleTaskEntity>().eq(AleTaskEntity::getRunId, runId));
        if (results.isEmpty()) {
            // Fallback: mark all as completed if no oracle data available
            for (AleTaskEntity task : tasks) {
                markTaskStatus(task.getId(), "COMPLETED", null, null, null);
            }
            return;
        }
        // Match results to tasks by taskId suffix
        for (AleTaskEntity task : tasks) {
            OracleTaskResult matched = findOracleResult(results, task.getTaskId());
            if (matched != null) {
                String status = mapOracleStatus(matched.status());
                String blockedReason = "blocked".equals(matched.status()) ? matched.blockedReason() : null;
                markTaskStatus(task.getId(), status, matched.oracleScore(),
                        matched.evidencePath(), blockedReason);
            } else {
                markTaskStatus(task.getId(), "FAILED", null, null,
                        "no oracle result found for task");
            }
        }
    }

    private OracleTaskResult findOracleResult(List<OracleTaskResult> results, String taskId) {
        for (OracleTaskResult r : results) {
            if (taskId.equals(r.taskId())) {
                return r;
            }
        }
        return null;
    }

    private String mapOracleStatus(String oracleStatus) {
        return switch (oracleStatus) {
            case "verified" -> "COMPLETED";
            case "blocked" -> "BLOCKED";
            default -> "FAILED";
        };
    }

    private void updateTaskCounts(Long runId, List<OracleTaskResult> results) {
        AleRunEntity run = new AleRunEntity();
        run.setId(runId);
        if (results.isEmpty()) {
            List<AleTaskEntity> tasks = taskMapper.selectList(
                    new LambdaQueryWrapper<AleTaskEntity>().eq(AleTaskEntity::getRunId, runId));
            run.setCompletedTasks(tasks.size());
            run.setFailedTasks(0);
            run.setBlockedTasks(0);
        } else {
            long completed = results.stream().filter(r -> "verified".equals(r.status())).count();
            long blocked = results.stream().filter(r -> "blocked".equals(r.status())).count();
            long failed = results.size() - completed - blocked;
            run.setCompletedTasks((int) completed);
            run.setFailedTasks((int) failed);
            run.setBlockedTasks((int) blocked);
        }
        runMapper.updateById(run);
    }

    private void updateTaskCountsToFailed(Long runId) {
        List<AleTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AleTaskEntity>().eq(AleTaskEntity::getRunId, runId));
        AleRunEntity run = new AleRunEntity();
        run.setId(runId);
        run.setFailedTasks(tasks.size());
        run.setCompletedTasks(0);
        run.setBlockedTasks(0);
        runMapper.updateById(run);
    }

    private boolean hasFailedTasks(List<OracleTaskResult> results) {
        return results.stream().anyMatch(r -> "failed".equals(r.status()));
    }

    private boolean allTasksBlocked(List<OracleTaskResult> results) {
        return !results.isEmpty() && results.stream().allMatch(r -> "blocked".equals(r.status()));
    }

    /**
     * Parse oracle validation results from summary.json written by the Python runner.
     */
    @SuppressWarnings("unchecked")
    private List<OracleTaskResult> parseOracleResults(Path runDir, List<AleTaskEntity> tasks) {
        Path summaryPath = runDir.resolve("summary.json");
        if (!Files.exists(summaryPath)) {
            return List.of();
        }
        try {
            String raw = Files.readString(summaryPath, StandardCharsets.UTF_8);
            Map<String, Object> summary = com.alibaba.fastjson2.JSON.parseObject(raw, Map.class);
            Map<String, Object> oracleValidation = (Map<String, Object>) summary.get("oracle_validation");
            if (oracleValidation == null) {
                return List.of();
            }
            List<Map<String, Object>> byTask = (List<Map<String, Object>>) oracleValidation.get("by_task");
            if (byTask == null || byTask.isEmpty()) {
                return List.of();
            }
            List<OracleTaskResult> results = new ArrayList<>();
            for (Map<String, Object> entry : byTask) {
                String taskId = stringField(entry, "task_id");
                String status = stringField(entry, "status");
                Double oracleScore = doubleField(entry, "oracle_score");
                String evidencePath = stringField(entry, "evidence_path");
                boolean dryRunOk = Boolean.TRUE.equals(entry.get("dry_run_ok"));
                boolean taskLoaderOk = Boolean.TRUE.equals(entry.get("task_loader_ok"));
                boolean evidenceOk = Boolean.TRUE.equals(entry.get("evidence_ok"));
                String blockedReason = stringField(entry, "blocked_reason");
                results.add(new OracleTaskResult(taskId, status, oracleScore, evidencePath,
                        dryRunOk, taskLoaderOk, evidenceOk, blockedReason));
            }
            return results;
        } catch (Exception e) {
            log.warn("Failed to parse oracle results from summary.json", e);
            return List.of();
        }
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : (value != null ? value.toString() : null);
    }

    private static Double doubleField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        return null;
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

    private void writeSummaryIfMissing(Path runDir, Long runId, AleRunRequest request, List<AleTaskEntity> tasks, String status, String errorMessage) throws IOException {
        Path summaryPath = runDir.resolve("summary.json");
        if (Files.exists(summaryPath)) {
            return;
        }
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
        Files.writeString(summaryPath, toJson(summary), StandardCharsets.UTF_8);
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

    private List<AleOptionDTO> codexModelOptions() {
        return properties.getCodexModels().stream()
                .map(m -> new AleOptionDTO(m, m))
                .toList();
    }

    private String buildRunKey(AleRunRequest request) {
        return request.getDomain() + "__" + request.getScenario() + "__" + UUID.randomUUID().toString().substring(0, 8);
    }

    private int resolveTargetCount(AleRunRequest request) {
        return request.getTargetCount() == null || request.getTargetCount() <= 0
                ? DEFAULT_TARGET_COUNT
                : request.getTargetCount();
    }

    private Path runDirectory(String runKey) {
        return Path.of(properties.getOutputRoot()).resolve(runKey);
    }

    private String runDirectoryPlaceholder(String domain, String taskName) {
        return "tasks/" + domain + "/" + taskName;
    }

    private Path frameworkRoot() {
        return Path.of(properties.getFrameworkRoot()).toAbsolutePath().normalize();
    }

    private String toJson(Object value) {
        return com.alibaba.fastjson2.JSON.toJSONString(value, com.alibaba.fastjson2.JSONWriter.Feature.PrettyFormat);
    }
}
