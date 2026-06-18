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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int DEFAULT_TARGET_COUNT = 1;
    private static final String SKILL_ROOT = "codex-skills/ale-task-factory";

    private final AleRunMapper runMapper;
    private final AleTaskMapper taskMapper;
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
            markTasksStatus(runId, "RUNNING", null);

            Path logPath = runDir.resolve("codex.log");
            List<String> command = buildCommand(planPath, runDir, request);
            int exitCode = runCodex(command, Path.of(".").toAbsolutePath().normalize(), logPath, runDir, runId);
            // Parse the enhanced summary.json for per-task oracle results
            List<OracleTaskResult> oracleResults = parseOracleResults(runDir, tasks);
            applyOracleResults(runId, oracleResults);
            updateTaskCounts(runId, oracleResults);

            if (exitCode == 0 && !hasFailedTasks(oracleResults)) {
                writeSummaryIfMissing(runDir, runId, request, tasks, "COMPLETED", null);
                updateRun(runId, AleRunStatus.COMPLETED, 100, null);
            } else if (exitCode == 0 && allTasksBlocked(oracleResults)) {
                String message = "all tasks blocked by oracle validation";
                writeSummaryIfMissing(runDir, runId, request, tasks, "BLOCKED", message);
                updateRun(runId, AleRunStatus.BLOCKED, 100, message);
            } else if (exitCode == 0) {
                String message = "some tasks failed or blocked — see per-task status";
                writeSummaryIfMissing(runDir, runId, request, tasks, "COMPLETED", message);
                updateRun(runId, AleRunStatus.COMPLETED, 100, message);
            } else {
                String message = "codex exit code " + exitCode;
                markTasksStatus(runId, "FAILED", message);
                writeSummaryIfMissing(runDir, runId, request, tasks, "FAILED", message);
                updateRun(runId, AleRunStatus.FAILED, 100, message);
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
        // taskId format: "domain/task_name" — try exact match first, then suffix match
        for (OracleTaskResult r : results) {
            if (taskId.equals(r.taskId())) {
                return r;
            }
        }
        for (OracleTaskResult r : results) {
            if (taskId.endsWith(r.taskId()) || r.taskId().endsWith(taskId)) {
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

    private void updateProgress(Long runId, int progress) {
        AleRunEntity update = new AleRunEntity();
        update.setId(runId);
        update.setProgressPercent(progress);
        update.setUpdatedAt(LocalDateTime.now());
        runMapper.updateById(update);
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

    private List<String> buildCommand(Path planPath, Path runDir, AleRunRequest request) {
        List<String> command = new ArrayList<>();
        command.add(properties.getCodexBinary());
        command.add("exec");
        command.add("--cd");
        command.add(Path.of(".").toAbsolutePath().normalize().toString());
        command.add("--dangerously-bypass-approvals-and-sandbox");
        command.add("--model");
        command.add(StringUtils.hasText(request.getCodexModel()) ? request.getCodexModel() : DEFAULT_MODEL);
        command.add("Use the ALE task factory skill to generate the run described in " + planPath.toAbsolutePath()
                + ". Use ALE framework root " + frameworkRoot().toAbsolutePath()
                + ". Write logs and artifacts under " + runDir.toAbsolutePath()
                + ". Do not run stage-2 model evaluation.");
        return command;
    }

    private int runCodex(List<String> command, Path cwd, Path logPath, Path runDir, Long runId) throws IOException, InterruptedException {
        Files.writeString(logPath,
                "$ " + String.join(" ", command) + System.lineSeparator()
                        + "ALE_OUTPUT_ROOT=" + runDir.toAbsolutePath() + System.lineSeparator()
                        + "ALE_FRAMEWORK_ROOT=" + frameworkRoot().toAbsolutePath() + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logPath.toFile()));
        Map<String, String> env = builder.environment();
        env.put("ALE_OUTPUT_ROOT", runDir.toAbsolutePath().toString());
        env.put("ALE_FRAMEWORK_ROOT", frameworkRoot().toAbsolutePath().toString());
        env.put("ALE_STAGE1", "true");
        Process process = builder.start();
        process.getOutputStream().close();
        int lastProgress = 25;
        updateProgress(runId, lastProgress);
        while (process.isAlive()) {
            Thread.sleep(3000);
            int nextProgress = Math.max(lastProgress, estimateProgress(runDir, logPath));
            if (nextProgress > lastProgress) {
                lastProgress = nextProgress;
                updateProgress(runId, lastProgress);
            }
        }
        int exit = process.waitFor();
        Files.writeString(logPath, System.lineSeparator() + "exitCode=" + exit + System.lineSeparator(), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        return exit;
    }

    private int estimateProgress(Path runDir, Path logPath) {
        int progress = 25;
        long logSize = logSize(logPath);
        if (logSize > 0) {
            progress = 35;
        }
        if (logSize > 4_000) {
            progress = 45;
        }
        if (logSize > 16_000) {
            progress = 55;
        }
        if (Files.exists(runDir.resolve("generated/stages/brief.md"))) {
            progress = Math.max(progress, 60);
        }
        if (Files.exists(runDir.resolve("generated/stages/draft.md"))) {
            progress = Math.max(progress, 70);
        }
        if (Files.exists(runDir.resolve("generated/stages/scaffold.md"))) {
            progress = Math.max(progress, 80);
        }
        if (hasGeneratedTasks(runDir)) {
            progress = Math.max(progress, 88);
        }
        if (Files.exists(runDir.resolve("summary.json"))) {
            progress = Math.max(progress, 95);
        }
        // Oracle evidence files indicate per-task validation has run
        if (hasOracleEvidence(runDir)) {
            progress = Math.max(progress, 98);
        }
        return progress;
    }

    private long logSize(Path logPath) {
        try {
            return Files.exists(logPath) ? Files.size(logPath) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private boolean hasGeneratedTasks(Path runDir) {
        try (var paths = Files.find(runDir, 5, (path, attrs) ->
                attrs.isRegularFile() && ("main.py".equals(path.getFileName().toString())
                        || "task_card.json".equals(path.getFileName().toString())))) {
            return paths.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    private boolean hasOracleEvidence(Path runDir) {
        try (var paths = Files.find(runDir, 8, (path, attrs) ->
                attrs.isRegularFile() && "oracle-evidence.json".equals(path.getFileName().toString()))) {
            return paths.findFirst().isPresent();
        } catch (IOException e) {
            return false;
        }
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
        Set<String> models = new LinkedHashSet<>();
        Path configPath = codexConfigPath();
        if (Files.exists(configPath)) {
            try {
                String section = "";
                for (String rawLine : Files.readAllLines(configPath, StandardCharsets.UTF_8)) {
                    String line = rawLine.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    if (line.startsWith("[") && line.endsWith("]")) {
                        section = line.substring(1, line.length() - 1);
                        continue;
                    }
                    if (section.isEmpty() && line.startsWith("model")) {
                        addTomlKeyValueModel(models, line);
                    } else if ("tui.model_availability_nux".equals(section)) {
                        addTomlQuotedKeyModel(models, line);
                    }
                }
            } catch (IOException e) {
                log.warn("failed to read Codex config {}", configPath, e);
            }
        }
        if (models.isEmpty()) {
            models.add(DEFAULT_MODEL);
            models.add("gpt-5-mini");
            models.add("gpt-5-codex");
        }
        return models.stream().map(model -> new AleOptionDTO(model, model)).toList();
    }

    private Path codexConfigPath() {
        String codexHome = System.getenv("CODEX_HOME");
        Path home = StringUtils.hasText(codexHome)
                ? Path.of(codexHome)
                : Path.of(System.getProperty("user.home"), ".codex");
        return home.resolve("config.toml");
    }

    private void addTomlKeyValueModel(Set<String> models, String line) {
        int index = line.indexOf('=');
        if (index < 0) {
            return;
        }
        String key = line.substring(0, index).trim();
        if (!"model".equals(key)) {
            return;
        }
        addTomlString(models, line.substring(index + 1));
    }

    private void addTomlQuotedKeyModel(Set<String> models, String line) {
        int end = line.indexOf('"', 1);
        if (!line.startsWith("\"") || end <= 1) {
            return;
        }
        models.add(line.substring(1, end));
    }

    private void addTomlString(Set<String> models, String rawValue) {
        String value = rawValue.trim();
        if (value.startsWith("\"")) {
            int end = value.indexOf('"', 1);
            if (end > 1) {
                models.add(value.substring(1, end));
            }
        }
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

    private String buildPlanJson(AleRunRequest request, Path runDir) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("runKey", runDir.getFileName().toString());
        plan.put("request", request);
        plan.put("outputRoot", runDir.toAbsolutePath().toString());
        plan.put("skillRoot", SKILL_ROOT);
        plan.put("frameworkRoot", frameworkRoot().toString());
        plan.put("frameworkTasksRoot", frameworkRoot().resolve("tasks").toString());
        plan.put("steps", List.of("brief", "draft", "scaffold", "oracle_validate"));
        plan.put("requirements", Map.of(
                "aleNativeMainPy", true,
                "oracleMustPassBeforeStage2", true,
                "noStage2ModelEvaluation", true,
                "summaryJsonMustIncludeEvidence", true));
        return toJson(plan);
    }
}
