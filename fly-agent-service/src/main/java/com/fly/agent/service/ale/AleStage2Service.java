package com.fly.agent.service.ale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fly.agent.common.dto.ale.AleClaudeCodeConfigDTO;
import com.fly.agent.common.dto.ale.AleClaudeCodeConfigRequest;
import com.fly.agent.common.dto.ale.AleRunDTO;
import com.fly.agent.common.dto.ale.AleRunSummaryDTO;
import com.fly.agent.common.dto.ale.AleStage2ReviewDTO;
import com.fly.agent.common.dto.ale.AleStage2TaskReviewDTO;
import com.fly.agent.common.dto.ale.AleTaskDTO;
import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.entity.ale.AleTaskEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AleStage2Service {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final AleRunMapper runMapper;
    private final AleTaskMapper taskMapper;
    private final AleExecutionGateway gateway;
    private final AleProperties properties;

    /** Start stage-2 execution for a completed stage-1 run. */
    public AleRunDTO startStage2(Long runId) {
        return startStage2(runId, List.of());
    }

    /** Re-run one Stage-2 task after clearing only that task's stale artifacts. */
    public AleRunDTO startStage2Task(Long runId, Long taskRowId) {
        AleTaskEntity task = taskMapper.selectById(taskRowId);
        if (task == null || !runId.equals(task.getRunId())) {
            throw new IllegalArgumentException("task not found for run: " + taskRowId);
        }
        return startStage2(runId, List.of(task.getTaskId()));
    }

    private AleRunDTO startStage2(Long runId, List<String> taskIds) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) {
            throw new IllegalArgumentException("run not found: " + runId);
        }
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new IllegalStateException("stage1 must be COMPLETED, current: " + run.getStatus());
        }
        if ("RUNNING".equals(run.getStage2Status())) {
            throw new IllegalStateException("stage2 is already RUNNING");
        }

        Path runDir = Path.of(run.getOutputRoot());
        boolean singleTaskRun = taskIds != null && !taskIds.isEmpty();
        if (singleTaskRun) {
            taskIds.forEach(taskId -> clearTaskStage2Artifacts(runDir, taskId));
        } else {
            clearStage2Artifacts(runDir);
        }

        // Mark stage2 as running and clear stale result fields from the selected attempt.
        runMapper.update(null, new UpdateWrapper<AleRunEntity>()
                .eq("id", runId)
                .set("stage2_status", "RUNNING")
                .set("stage2_progress", 0)
                .set("stage2_started_at", LocalDateTime.now())
                .set("stage2_finished_at", null)
                .set("stage2_summary_path", null)
                .set("error_message", null));
        UpdateWrapper<AleTaskEntity> taskUpdate = new UpdateWrapper<AleTaskEntity>()
                .eq("run_id", runId)
                .set("stage2_status", null)
                .set("stage2_score", null)
                .set("stage2_duration_s", null)
                .set("stage2_result_dir", null)
                .set("stage2_error", null);
        if (singleTaskRun) taskUpdate.in("task_id", taskIds);
        taskMapper.update(null, taskUpdate);

        List<String> selectedTaskIds = taskIds == null ? List.of() : List.copyOf(taskIds);
        EXECUTOR.submit(() -> executeStage2(runId, selectedTaskIds));
        return getRun(runId);
    }

    public AleRunDTO getRun(Long id) {
        AleRunEntity run = runMapper.selectById(id);
        if (run == null) return null;
        return toRunDTO(run);
    }

    public List<AleRunSummaryDTO> listSummaries() {
        List<AleRunEntity> runs = runMapper.selectList(
                new LambdaQueryWrapper<AleRunEntity>().orderByDesc(AleRunEntity::getCreatedAt));
        return runs.stream().map(this::toSummaryDTO).collect(Collectors.toList());
    }

    public List<AleTaskDTO> listTasks(Long runId) {
        List<AleTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AleTaskEntity>()
                        .eq(AleTaskEntity::getRunId, runId)
                        .orderByAsc(AleTaskEntity::getId));
        return tasks.stream().map(this::toTaskDTO).collect(Collectors.toList());
    }

    public List<String> tailLog(Long runId, int lines) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            return List.of();
        }
        return gateway.tailLog(Path.of(run.getOutputRoot()), "stage2", lines);
    }

    public List<String> tailAgentLog(Long runId, int lines) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            return List.of();
        }
        Path transcript = latestAgentTranscript(Path.of(run.getOutputRoot()));
        if (transcript == null) {
            return List.of("暂无 agent transcript；Stage 2 启动后会自动刷新。");
        }
        List<String> rendered = renderTranscript(transcript);
        int from = Math.max(0, rendered.size() - Math.max(lines, 1));
        return new ArrayList<>(rendered.subList(from, rendered.size()));
    }

    public AleClaudeCodeConfigDTO getClaudeCodeConfig() {
        return readClaudeCodeConfig();
    }

    public AleClaudeCodeConfigDTO saveClaudeCodeConfig(AleClaudeCodeConfigRequest request) {
        if (request == null) request = new AleClaudeCodeConfigRequest();
        AleClaudeCodeConfigDTO current = readClaudeCodeConfig();
        String model = hasText(request.getModel()) ? request.getModel().trim() : current.getModel();
        String provider = hasText(request.getProvider()) ? request.getProvider().trim() : current.getProvider();
        String baseUrl = hasText(request.getBaseUrl()) ? request.getBaseUrl().trim() : current.getBaseUrl();
        String cliVersion = hasText(request.getCliVersion()) ? request.getCliVersion().trim() : current.getCliVersion();
        Integer maxThinkingTokens = request.getMaxThinkingTokens() != null
                ? request.getMaxThinkingTokens() : current.getMaxThinkingTokens();

        writeClaudeYaml(model, provider, baseUrl, cliVersion, maxThinkingTokens);
        writeSecretEnv(request, model, baseUrl);
        return readClaudeCodeConfig();
    }

    public byte[] buildArtifactsZip(Long runId) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            throw new IllegalArgumentException("run output not found: " + runId);
        }
        Path runDir = Path.of(run.getOutputRoot());
        if (!Files.isDirectory(runDir)) {
            throw new IllegalArgumentException("run dir not found: " + runDir);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                addIfExists(zip, runDir.resolve("summary.json"), "stage1/summary.json");
                addTree(zip, runDir.resolve("tasks"), "stage1/tasks");
                addTree(zip, runDir.resolve("oracle-logs"), "stage1/oracle-logs");
                addIfExists(zip, runDir.resolve("exp.yaml"), "stage2/exp.yaml");
                addIfExists(zip, runDir.resolve("stage2_progress.json"), "stage2_progress.json");
                addIfExists(zip, runDir.resolve("stage2_summary.json"), "stage2_summary.json");
                addIfExists(zip, runDir.resolve("stage2.log"), "stage2.log");
                addTree(zip, runDir.resolve("results"), "results");
                addTree(zip, runDir.resolve("logs/ale"), "stage2/logs/ale");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to build artifacts zip: " + e.getMessage(), e);
        }
    }

    public byte[] buildTaskArtifactsZip(Long runId, Long taskRowId) {
        AleRunEntity run = runMapper.selectById(runId);
        AleTaskEntity task = taskMapper.selectById(taskRowId);
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            throw new IllegalArgumentException("run output not found: " + runId);
        }
        if (task == null || !runId.equals(task.getRunId())) {
            throw new IllegalArgumentException("task not found for run: " + taskRowId);
        }
        Path runDir = Path.of(run.getOutputRoot());
        if (!Files.isDirectory(runDir)) {
            throw new IllegalArgumentException("run dir not found: " + runDir);
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
                addTaskPackageForDelivery(zip, runDir, task.getTaskId());
                String taskRoot = task.getTaskId();
                addOracleArtifactsForTask(zip, runDir, task.getTaskId(), taskRoot + "/oracle");
                addIfExists(zip, runDir.resolve("exp.yaml"), taskRoot + "/ale/exp.yaml");
                addIfExists(zip, runDir.resolve("stage2_progress.json"), taskRoot + "/ale/stage2_progress.json");
                addIfExists(zip, runDir.resolve("stage2_summary.json"), taskRoot + "/ale/stage2_summary.json");
                addIfExists(zip, runDir.resolve("stage2.log"), taskRoot + "/ale/stage2.log");
                addTree(zip, resultDirFor(runDir, task.getTaskId()), taskRoot + "/ale");
                addAleRunDirsForTask(zip, runDir, task.getTaskId(), taskRoot + "/ale/runs");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("failed to build task artifacts zip: " + e.getMessage(), e);
        }
    }

    public AleStage2ReviewDTO reviewStage2(Long runId) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            throw new IllegalArgumentException("run output not found: " + runId);
        }
        Path runDir = Path.of(run.getOutputRoot());
        AleStage2ReviewDTO dto = new AleStage2ReviewDTO();
        dto.setRunId(runId);
        dto.setRunKey(run.getRunKey());
        dto.setStatus(run.getStage2Status());
        dto.setAnalysisSource("artifact-rule-v1");
        dto.setArtifactHint("下载 Stage 2 产物可查看完整 result.json、eval_result.json、agent transcript 和输出文件。");

        List<AleTaskEntity> tasks = taskMapper.selectList(
                new LambdaQueryWrapper<AleTaskEntity>()
                        .eq(AleTaskEntity::getRunId, runId)
                        .orderByAsc(AleTaskEntity::getId));
        BigDecimal scoreSum = BigDecimal.ZERO;
        int scoreCount = 0;
        int attentionCount = 0;
        for (AleTaskEntity task : tasks) {
            AleStage2TaskReviewDTO item = reviewTask(runDir, task);
            dto.getTasks().add(item);
            if (item.getScore() != null) {
                scoreSum = scoreSum.add(item.getScore());
                scoreCount++;
            }
            if (Boolean.TRUE.equals(item.getNeedsAttention())) attentionCount++;
        }
        if (scoreCount > 0) {
            dto.setAverageScore(scoreSum.divide(BigDecimal.valueOf(scoreCount), 3, java.math.RoundingMode.HALF_UP));
        }
        boolean runFailed = "FAILED".equalsIgnoreCase(run.getStage2Status());
        boolean lowScore = dto.getAverageScore() != null && dto.getAverageScore().compareTo(BigDecimal.valueOf(0.95)) < 0;
        dto.setNeedsAttention(runFailed || lowScore || attentionCount > 0);
        dto.setSummary(buildRunReviewSummary(dto, runFailed, lowScore, attentionCount));
        dto.setLikelyCauses(mergeCauses(dto.getTasks()));
        dto.setSuggestedFixes(mergeFixes(dto.getTasks()));
        if (dto.getLikelyCauses().isEmpty() && Boolean.TRUE.equals(dto.getNeedsAttention())) {
            dto.getLikelyCauses().add("Stage 2 已完成但分数偏低，需查看 evaluator report 和 agent 输出差异。");
        }
        if (dto.getSuggestedFixes().isEmpty() && Boolean.TRUE.equals(dto.getNeedsAttention())) {
            dto.getSuggestedFixes().add("先下载产物，检查 eval_result.json、stage2.log 和 agent-log/output，再修正任务可见约束或 grader。");
        }
        return dto;
    }

    public AleStage2TaskReviewDTO reviewStage2Task(Long runId, Long taskRowId) {
        AleRunEntity run = runMapper.selectById(runId);
        AleTaskEntity task = taskMapper.selectById(taskRowId);
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            throw new IllegalArgumentException("run output not found: " + runId);
        }
        if (task == null || !runId.equals(task.getRunId())) {
            throw new IllegalArgumentException("task not found for run: " + taskRowId);
        }
        return reviewTask(Path.of(run.getOutputRoot()), task);
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private void executeStage2(Long runId, List<String> taskIds) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) return;
        Path runDir = Path.of(run.getOutputRoot());
        try {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", "stage2");
            payload.put("run_id", runId);
            payload.put("run_dir", runDir.toString());
            Map<String, Object> s2 = new java.util.LinkedHashMap<>();
            s2.put("framework_root", properties.getFrameworkRoot());
            s2.put("agent", "claude_code");
            s2.put("model", readClaudeCodeConfig().getModel());
            s2.put("timeout", 7200);
            if (taskIds != null && !taskIds.isEmpty()) s2.put("task_ids", taskIds);
            payload.put("stage2", s2);

            AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                    runId, runDir, payload, p -> updateStage2Progress(runId, p));

            Path summaryPath = runDir.resolve("stage2_summary.json");
            if (Files.exists(summaryPath)) {
                parseAndApplyResults(runId, runDir, summaryPath);
            }

            AleRunEntity update = new AleRunEntity();
            update.setId(runId);
            update.setStage2Status(result.isDone() ? "COMPLETED" : "FAILED");
            update.setStage2Progress(100);
            update.setStage2FinishedAt(LocalDateTime.now());
            update.setStage2SummaryPath(summaryPath.toString());
            update.setErrorMessage(result.isFailed() ? result.message() : null);
            if (result.isFailed()) update.setErrorMessage(result.message());
            runMapper.updateById(update);
        } catch (Exception e) {
            log.error("Stage2 execution failed", e);
            AleRunEntity update = new AleRunEntity();
            update.setId(runId);
            update.setStage2Status("FAILED");
            update.setStage2Progress(100);
            update.setStage2FinishedAt(LocalDateTime.now());
            runMapper.updateById(update);
        }
    }

    private void clearStage2Artifacts(Path runDir) {
        if (runDir == null || !Files.isDirectory(runDir)) return;
        deleteIfExists(runDir.resolve("stage2_progress.json"));
        deleteIfExists(runDir.resolve("stage2_summary.json"));
        deleteIfExists(runDir.resolve("stage2.log"));
        deleteIfExists(runDir.resolve("results"));
        deleteIfExists(runDir.resolve("logs/ale"));
    }

    private void clearTaskStage2Artifacts(Path runDir, String taskId) {
        if (runDir == null || !Files.isDirectory(runDir) || !hasText(taskId)) return;
        deleteIfExists(resultDirFor(runDir, taskId));
        deleteIfExists(runDir.resolve("stage2_progress.json"));
        deleteIfExists(runDir.resolve("stage2_summary.json"));
        deleteIfExists(runDir.resolve("stage2.log"));
        Path logRoot = runDir.resolve("logs/ale");
        if (!Files.isDirectory(logRoot)) return;
        try (Stream<Path> stream = Files.walk(logRoot)) {
            for (Path dir : stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.toString().contains(taskSlug(taskId)))
                    .filter(p -> Files.exists(p.resolve("run.json"))
                            || Files.exists(p.resolve("origin_log/claude-code/transcript.jsonl")))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                deleteIfExists(dir);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to clear stale stage2 task artifacts for " + taskId + ": " + e.getMessage(), e);
        }
    }

    private void deleteIfExists(Path path) {
        if (!Files.exists(path)) return;
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> stream = Files.walk(path)) {
                    for (Path child : stream.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(child);
                    }
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to clear stale stage2 artifact " + path + ": " + e.getMessage(), e);
        }
    }

    private AleStage2TaskReviewDTO reviewTask(Path runDir, AleTaskEntity task) {
        AleStage2TaskReviewDTO dto = new AleStage2TaskReviewDTO();
        dto.setTaskId(task.getTaskId());
        dto.setStatus(task.getStage2Status());
        dto.setScore(task.getStage2Score());
        dto.setError(task.getStage2Error());
        boolean needsAttention = !"completed".equalsIgnoreCase(defaultString(task.getStage2Status(), ""))
                || task.getStage2Score() == null
                || task.getStage2Score().compareTo(BigDecimal.valueOf(0.95)) < 0
                || hasText(task.getStage2Error());
        dto.setNeedsAttention(needsAttention);

        Path resultPath = resultPathFor(runDir, task.getTaskId());
        Map<String, Object> result = readJsonMap(resultPath);
        if (result != null && !hasText(dto.getError())) dto.setError(stringField(result, "error"));

        Path latest = latestAleRunDirForTask(runDir, task.getTaskId());
        Map<String, Object> eval = latest == null ? null : readJsonMap(latest.resolve("eval_result.json"));
        Map<String, Object> runJson = latest == null ? null : readJsonMap(latest.resolve("run.json"));

        addEvidence(dto, resultPath, "result.json");
        if (latest != null) {
            addEvidence(dto, latest.resolve("eval_result.json"), "eval_result.json");
            addEvidence(dto, latest.resolve("run.json"), "run.json");
        }

        collectReviewSignals(dto, result, eval, runJson, runDir);
        if (!needsAttention) {
            dto.setSummary("Stage 2 通过，分数满足阈值。");
            if (dto.getEvidence().isEmpty()) dto.getEvidence().add("任务状态 completed，score=" + task.getStage2Score());
            return dto;
        }
        if (!hasText(dto.getSummary())) {
            dto.setSummary(compactReview("Stage 2 未达到通过阈值。"
                    + (dto.getScore() == null ? " 未采集到有效分数。" : " 当前分数 " + dto.getScore() + "。")
                    + (hasText(dto.getError()) ? " 错误：" + dto.getError() : "")));
        }
        if (dto.getSuggestedFixes().isEmpty()) {
            dto.getSuggestedFixes().add("检查 task 的可见说明、grader_contract、输出 schema 和 hidden reference 的一致性，然后重新测评。");
        }
        return dto;
    }

    private void collectReviewSignals(
            AleStage2TaskReviewDTO dto,
            Map<String, Object> result,
            Map<String, Object> eval,
            Map<String, Object> runJson,
            Path runDir
    ) {
        String error = firstText(
                dto.getError(),
                result == null ? null : stringField(result, "error"),
                runJson == null ? null : stringField(runJson, "error"),
                eval == null ? null : stringField(eval, "error"));
        if (hasText(error)) {
            dto.setSummary(compactReview("执行或评分报错：" + error));
            dto.getEvidence().add(compactReview(error));
            dto.getSuggestedFixes().add("优先修复异常栈指向的 scorer、环境或输出格式问题，再重新测评。");
        }

        List<String> logSignals = extractLogSignals(runDir.resolve("stage2.log"));
        for (String signal : logSignals) {
            if (dto.getEvidence().size() < 8) dto.getEvidence().add(signal);
            String lower = signal.toLowerCase();
            if (lower.contains("scoring failed") || lower.contains("traceback") || lower.contains("exception")) {
                dto.setSummary(compactReview("评分阶段异常：" + signal));
                dto.getSuggestedFixes().add("查看 scorer traceback，修正任务输出契约或 scorer 对异常输入的处理。");
            } else if (lower.contains("evaluation report")) {
                dto.setSummary(compactReview(signal));
                dto.getSuggestedFixes().add("根据 evaluation report 中 false 的检查项，补齐可见要求或修正 agent 输出文件。");
            } else if (lower.contains("exit code")) {
                dto.getSuggestedFixes().add("检查 ale_run 退出码附近日志，确认 Docker、Claude Code、任务 setup 和 evaluate 是否正常。");
            }
        }

        if (eval != null && !eval.isEmpty()) {
            dto.getEvidence().add("eval_result.json: " + compactReview(JSON.toJSONString(eval)));
        }
        if (runJson != null && !runJson.isEmpty()) {
            Object score = runJson.get("score");
            Object status = runJson.get("status");
            dto.getEvidence().add("run.json: status=" + status + ", score=" + score);
        }
    }

    private Path resultPathFor(Path runDir, String taskId) {
        return resultDirFor(runDir, taskId).resolve("result.json");
    }

    private Path resultDirFor(Path runDir, String taskId) {
        if (!hasText(taskId)) return runDir.resolve("results");
        return runDir.resolve("results").resolve(taskSlug(taskId));
    }

    private String taskSlug(String taskId) {
        return taskId.replace("/", "__");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(Path path) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            return JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8), Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void addEvidence(AleStage2TaskReviewDTO dto, Path path, String label) {
        if (path != null && Files.isRegularFile(path)) dto.getEvidence().add(label + ": " + path);
    }

    private List<String> extractLogSignals(Path logPath) {
        if (!Files.isRegularFile(logPath)) return List.of();
        try {
            List<String> lines = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            List<String> signals = new ArrayList<>();
            for (String line : lines) {
                String lower = line.toLowerCase();
                if (lower.contains("evaluation report")
                        || lower.contains("scoring failed")
                        || lower.contains("traceback")
                        || lower.contains("error")
                        || lower.contains("exit code")
                        || lower.contains("score=")) {
                    signals.add(compactReview(line));
                }
            }
            int from = Math.max(0, signals.size() - 8);
            return new ArrayList<>(signals.subList(from, signals.size()));
        } catch (IOException e) {
            return List.of();
        }
    }

    private String buildRunReviewSummary(AleStage2ReviewDTO dto, boolean runFailed, boolean lowScore, int attentionCount) {
        if (!Boolean.TRUE.equals(dto.getNeedsAttention())) {
            return "Stage 2 通过，当前没有需要整改的 task。";
        }
        if (runFailed) return "Stage 2 执行失败，需要先处理运行或评分错误，再重跑。";
        if (lowScore) return "Stage 2 已完成但均分偏低，建议根据回顾修正任务包后重跑。";
        return "Stage 2 有 " + attentionCount + " 个 task 需要关注。";
    }

    private List<String> mergeCauses(List<AleStage2TaskReviewDTO> tasks) {
        List<String> causes = new ArrayList<>();
        for (AleStage2TaskReviewDTO task : tasks) {
            if (!Boolean.TRUE.equals(task.getNeedsAttention())) continue;
            if (hasText(task.getSummary())) causes.add(task.getTaskId() + ": " + task.getSummary());
        }
        return dedupeLimit(causes, 6);
    }

    private List<String> mergeFixes(List<AleStage2TaskReviewDTO> tasks) {
        List<String> fixes = new ArrayList<>();
        for (AleStage2TaskReviewDTO task : tasks) {
            if (!Boolean.TRUE.equals(task.getNeedsAttention())) continue;
            fixes.addAll(task.getSuggestedFixes());
        }
        return dedupeLimit(fixes, 6);
    }

    private List<String> dedupeLimit(List<String> values, int limit) {
        if (values.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String value : values) {
            String compacted = compactReview(value);
            if (hasText(compacted) && seen.add(compacted)) out.add(compacted);
            if (out.size() >= limit) break;
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private void parseAndApplyResults(Long runId, Path runDir, Path summaryPath) {
        try {
            String raw = Files.readString(summaryPath, StandardCharsets.UTF_8);
            Map<String, Object> summary = com.alibaba.fastjson2.JSON.parseObject(raw, Map.class);
            List<AleTaskEntity> tasks = taskMapper.selectList(
                    new LambdaQueryWrapper<AleTaskEntity>().eq(AleTaskEntity::getRunId, runId));

            // Read per-task result.json files
            for (AleTaskEntity task : tasks) {
                String taskId = task.getTaskId(); // "domain/task_name"
                if (taskId == null) continue;
                String dirName = taskId.replace("/", "__");
                Path resultPath = runDir.resolve("results").resolve(dirName).resolve("result.json");
                if (!Files.exists(resultPath)) continue;

                try {
                    Map<String, Object> result = com.alibaba.fastjson2.JSON.parseObject(
                            Files.readString(resultPath), Map.class);
                    AleTaskEntity update = new AleTaskEntity();
                    update.setId(task.getId());
                    update.setStage2Status(stringField(result, "status"));
                    Double score = doubleField(result, "score");
                    if (score != null) update.setStage2Score(BigDecimal.valueOf(score));
                    Double duration = doubleField(result, "duration_s");
                    if (duration != null) update.setStage2DurationS(BigDecimal.valueOf(duration));
                    update.setStage2ResultDir(runDir.resolve("results").resolve(dirName).toString());
                    String error = stringField(result, "error");
                    if (error == null) {
                        taskMapper.update(null, new LambdaUpdateWrapper<AleTaskEntity>()
                                .eq(AleTaskEntity::getId, task.getId())
                                .set(AleTaskEntity::getStage2Status, update.getStage2Status())
                                .set(AleTaskEntity::getStage2Score, update.getStage2Score())
                                .set(AleTaskEntity::getStage2DurationS, update.getStage2DurationS())
                                .set(AleTaskEntity::getStage2ResultDir, update.getStage2ResultDir())
                                .set(AleTaskEntity::getStage2Error, null));
                    } else {
                        update.setStage2Error(error);
                        taskMapper.updateById(update);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse result for task {}", taskId, e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse stage2 summary", e);
        }
    }

    private void updateStage2Progress(Long runId, int progress) {
        AleRunEntity update = new AleRunEntity();
        update.setId(runId);
        update.setStage2Progress(progress);
        runMapper.updateById(update);
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    private AleRunDTO toRunDTO(AleRunEntity run) {
        AleRunDTO dto = new AleRunDTO();
        BeanUtils.copyProperties(run, dto);
        dto.setTasks(listTasks(run.getId()));
        return dto;
    }

    private AleRunSummaryDTO toSummaryDTO(AleRunEntity run) {
        AleRunSummaryDTO dto = new AleRunSummaryDTO();
        BeanUtils.copyProperties(run, dto);
        dto.setRunId(run.getId());
        return dto;
    }

    private AleTaskDTO toTaskDTO(AleTaskEntity entity) {
        AleTaskDTO dto = new AleTaskDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }

    private static String stringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String s ? s : (value != null ? value.toString() : null);
    }

    private static Double doubleField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.doubleValue();
        return null;
    }

    private AleClaudeCodeConfigDTO readClaudeCodeConfig() {
        AleClaudeCodeConfigDTO dto = new AleClaudeCodeConfigDTO();
        Path yaml = claudeYamlPath();
        if (Files.exists(yaml)) {
            try {
                for (String line : Files.readAllLines(yaml, StandardCharsets.UTF_8)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("model:")) dto.setModel(unquote(trimmed.substring("model:".length()).trim()));
                    if (trimmed.startsWith("provider:")) dto.setProvider(unquote(trimmed.substring("provider:".length()).trim()));
                    if (trimmed.startsWith("base_url:")) dto.setBaseUrl(unquote(trimmed.substring("base_url:".length()).trim()));
                    if (trimmed.startsWith("max_thinking_tokens:")) {
                        String raw = unquote(trimmed.substring("max_thinking_tokens:".length()).trim());
                        dto.setMaxThinkingTokens("null".equals(raw) ? null : Integer.valueOf(raw));
                    }
                    if (trimmed.startsWith("cli_version:")) dto.setCliVersion(unquote(trimmed.substring("cli_version:".length()).trim()));
                }
            } catch (Exception e) {
                log.warn("Failed to read Claude Code yaml {}", yaml, e);
            }
        }
        Map<String, String> env = readSecretEnv();
        String apiKey = env.getOrDefault("ANTHROPIC_API_KEY", "");
        String authToken = env.getOrDefault("ANTHROPIC_AUTH_TOKEN", "");
        if (!hasText(dto.getBaseUrl()) && hasText(env.get("ANTHROPIC_BASE_URL"))) dto.setBaseUrl(env.get("ANTHROPIC_BASE_URL"));
        if (!hasText(dto.getModel()) && hasText(env.get("ANTHROPIC_MODEL"))) dto.setModel(env.get("ANTHROPIC_MODEL"));
        if (!hasText(dto.getProvider())) dto.setProvider("direct");
        dto.setApiKeySet(hasText(apiKey));
        dto.setAuthTokenSet(hasText(authToken));
        dto.setApiKeyPreview(maskSecret(apiKey));
        dto.setAuthTokenPreview(maskSecret(authToken));
        return dto;
    }

    private void writeClaudeYaml(String model, String provider, String baseUrl, String cliVersion, Integer maxThinkingTokens) {
        Path yaml = claudeYamlPath();
        try {
            Files.createDirectories(yaml.getParent());
            String content = """
                    harness: claude_code
                    model: %s
                    config:
                      provider: %s
                      base_url: %s
                      max_turns: -1
                      max_budget_usd: null
                      dangerously_skip_permissions: true
                      max_thinking_tokens: %s
                      disabled_tools:
                      - EnterPlanMode
                      - ExitPlanMode
                      - EnterWorktree
                      - ExitWorktree
                      - AskUserQuestion
                      - TaskOutput
                      - TaskStop
                      - RemoteTrigger
                      cli_version: '%s'
                    """.formatted(
                    defaultString(model, "claude-opus-4-6"),
                    defaultString(provider, "direct"),
                    defaultString(baseUrl, "https://api.anthropic.com"),
                    maxThinkingTokens == null ? "null" : maxThinkingTokens.toString(),
                    defaultString(cliVersion, "@anthropic-ai/claude-code@2.1.178"));
            Files.writeString(yaml, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + yaml + ": " + e.getMessage(), e);
        }
    }

    private void writeSecretEnv(AleClaudeCodeConfigRequest request, String model, String baseUrl) {
        Path secret = secretEnvPath();
        Map<String, String> env = readSecretEnv();
        if (hasText(request.getApiKey())) env.put("ANTHROPIC_API_KEY", request.getApiKey().trim());
        if (hasText(request.getAuthToken())) env.put("ANTHROPIC_AUTH_TOKEN", request.getAuthToken().trim());
        if (!hasText(env.get("ANTHROPIC_API_KEY")) && hasText(env.get("ANTHROPIC_AUTH_TOKEN"))) {
            env.put("ANTHROPIC_API_KEY", env.get("ANTHROPIC_AUTH_TOKEN"));
        }
        if (!hasText(env.get("ANTHROPIC_AUTH_TOKEN")) && hasText(env.get("ANTHROPIC_API_KEY"))) {
            env.put("ANTHROPIC_AUTH_TOKEN", env.get("ANTHROPIC_API_KEY"));
        }
        env.put("ANTHROPIC_BASE_URL", defaultString(baseUrl, env.get("ANTHROPIC_BASE_URL")));
        env.put("ANTHROPIC_MODEL", defaultString(model, env.get("ANTHROPIC_MODEL")));
        env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", env.getOrDefault("ANTHROPIC_DEFAULT_HAIKU_MODEL", env.get("ANTHROPIC_MODEL")));
        env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", env.getOrDefault("ANTHROPIC_DEFAULT_SONNET_MODEL", env.get("ANTHROPIC_MODEL")));
        env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", env.getOrDefault("ANTHROPIC_DEFAULT_OPUS_MODEL", env.get("ANTHROPIC_MODEL")));
        env.putIfAbsent("OPENROUTER_API_KEY", "");
        env.putIfAbsent("OPENAI_API_KEY", "");
        env.putIfAbsent("BRAVE_API_KEY", "");

        List<String> ordered = List.of(
                "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL",
                "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL",
                "ANTHROPIC_DEFAULT_SONNET_MODEL", "ANTHROPIC_DEFAULT_OPUS_MODEL",
                "OPENROUTER_API_KEY", "OPENAI_API_KEY", "BRAVE_API_KEY");
        try {
            Files.createDirectories(secret.getParent());
            StringBuilder out = new StringBuilder();
            out.append("# ---- LLM API keys ----\n");
            out.append("# Managed by fly-agent ALE Stage 2 settings. Do not commit this file.\n");
            for (String key : ordered) out.append(key).append('=').append(env.getOrDefault(key, "")).append('\n');
            Files.writeString(secret, out.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to write " + secret + ": " + e.getMessage(), e);
        }
    }

    private Map<String, String> readSecretEnv() {
        Map<String, String> env = new LinkedHashMap<>();
        Path secret = secretEnvPath();
        if (!Files.exists(secret)) return env;
        try {
            for (String line : Files.readAllLines(secret, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) continue;
                int idx = trimmed.indexOf('=');
                env.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
            }
        } catch (IOException e) {
            log.warn("Failed to read {}", secret, e);
        }
        return env;
    }

    private Path latestAgentTranscript(Path runDir) {
        Path latest = latestAleRunDir(runDir);
        if (latest == null) return null;
        Path transcript = latest.resolve("origin_log/claude-code/transcript.jsonl");
        return Files.exists(transcript) ? transcript : null;
    }

    private Path latestAleRunDir(Path runDir) {
        Path root = runDir.resolve("logs/ale");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> Files.isRegularFile(p.resolve("run.json")) || Files.exists(p.resolve("origin_log/claude-code/transcript.jsonl")))
                    .max(Comparator.comparingLong(this::mtime))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private Path latestAleRunDirForTask(Path runDir, String taskId) {
        if (!hasText(taskId)) return latestAleRunDir(runDir);
        Path root = runDir.resolve("logs/ale");
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(p -> Files.isRegularFile(p.resolve("run.json")) || Files.exists(p.resolve("origin_log/claude-code/transcript.jsonl")))
                    .filter(p -> isAleRunDirForTask(p, taskId))
                    .max(Comparator.comparingLong(this::mtime))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> renderTranscript(Path transcript) {
        List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(transcript, StandardCharsets.UTF_8)) {
                if (!hasText(line)) continue;
                JSONObject entry = JSON.parseObject(line);
                String type = entry.getString("type");
                if ("system".equals(type) && "init".equals(entry.getString("subtype"))) {
                    out.add("[init] model=" + entry.getString("model")
                            + " key=" + entry.getString("apiKeySource")
                            + " claude=" + entry.getString("claude_code_version"));
                } else if ("assistant".equals(type) || "user".equals(type)) {
                    JSONObject msg = entry.getJSONObject("message");
                    if (msg == null) continue;
                    String role = msg.getString("role");
                    var content = msg.getJSONArray("content");
                    if (content == null) continue;
                    for (Object item : content) {
                        if (!(item instanceof JSONObject obj)) continue;
                        String ctype = obj.getString("type");
                        if ("text".equals(ctype)) out.add("[" + role + "] " + compact(obj.getString("text")));
                        if ("tool_use".equals(ctype)) out.add("[tool] " + obj.getString("name") + " " + compact(String.valueOf(obj.get("input"))));
                        if ("tool_result".equals(ctype)) out.add("[tool_result] " + compact(obj.getString("content")));
                    }
                } else if ("result".equals(type)) {
                    out.add("[result] error=" + entry.getBooleanValue("is_error")
                            + " status=" + entry.getString("subtype")
                            + " cost=" + entry.getString("total_cost_usd")
                            + " " + compact(entry.getString("result")));
                }
            }
        } catch (Exception e) {
            out.add("failed to parse transcript: " + e.getMessage());
        }
        return out;
    }

    private void addTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        if (!Files.exists(root)) return;
        Set<String> added = new HashSet<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                String name = prefix + "/" + root.relativize(path).toString().replace('\\', '/');
                if (added.add(name)) addFile(zip, path, name);
            }
        }
    }

    private void addAleRunDirsForTask(ZipOutputStream zip, Path runDir, String taskId, String prefix) throws IOException {
        Path root = runDir.resolve("logs/ale");
        if (!Files.isDirectory(root) || !hasText(taskId)) return;
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path aleDir : stream
                    .filter(p -> Files.isRegularFile(p.resolve("run.json"))
                            || Files.exists(p.resolve("origin_log/claude-code/transcript.jsonl")))
                    .filter(p -> isAleRunDirForTask(p, taskId))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList()) {
                addTree(zip, aleDir, prefix + "/" + aleRunDeliveryName(aleDir));
            }
        }
    }

    private String aleRunDeliveryName(Path aleDir) {
        Path timestamp = aleDir.getFileName();
        Path version = aleDir.getParent() == null ? null : aleDir.getParent().getFileName();
        if (timestamp == null) return "run";
        if (version == null) return timestamp.toString();
        return version + "/" + timestamp;
    }

    private void addTaskPackageForDelivery(ZipOutputStream zip, Path runDir, String taskId) throws IOException {
        Path taskDir = taskDirFor(runDir, taskId);
        if (taskDir == null || !Files.isDirectory(taskDir)) return;
        String prefix = taskId;
        try (Stream<Path> stream = Files.walk(taskDir)) {
            for (Path path : stream.filter(Files::isRegularFile).toList()) {
                Path relativePath = taskDir.relativize(path);
                if (isNonDeliveryTaskFile(relativePath)) continue;
                String relative = relativePath.toString().replace('\\', '/');
                addFile(zip, path, prefix + "/" + relative);
            }
        }
    }

    private void addOracleArtifactsForTask(ZipOutputStream zip, Path runDir, String taskId, String prefix) throws IOException {
        addIfExists(zip, runDir.resolve("summary.json"), prefix + "/summary.json");
        addIfExists(zip, runDir.resolve("stage1.log"), prefix + "/stage1.log");
        addIfExists(zip, runDir.resolve("stage1_progress.json"), prefix + "/stage1_progress.json");
        addIfExists(zip, runDir.resolve("request.json"), prefix + "/request.json");
        addIfExists(zip, runDir.resolve("plan.json"), prefix + "/plan.json");
        addIfExists(zip, runDir.resolve("dry_run_agent.yaml"), prefix + "/dry_run_agent.yaml");
        addIfExists(zip, runDir.resolve("dry_run_environment.yaml"), prefix + "/dry_run_environment.yaml");
        addIfExists(zip, runDir.resolve("dry_run_experiment.yaml"), prefix + "/dry_run_experiment.yaml");
        Path taskDir = taskDirFor(runDir, taskId);
        if (taskDir != null) {
            addTree(zip, taskDir.resolve("oracle-logs"), prefix + "/oracle-logs");
        }
    }

    private void addTaskPackage(ZipOutputStream zip, Path runDir, String taskId) throws IOException {
        Path taskDir = taskDirFor(runDir, taskId);
        if (taskDir == null) return;
        String[] parts = taskId.split("/", 2);
        addTree(zip, taskDir, "stage1/tasks/" + parts[0] + "/" + parts[1]);
    }

    private Path taskDirFor(Path runDir, String taskId) {
        if (!hasText(taskId) || !taskId.contains("/")) return null;
        String[] parts = taskId.split("/", 2);
        return runDir.resolve("tasks").resolve(parts[0]).resolve(parts[1]);
    }

    private boolean isNonDeliveryTaskFile(Path relativePath) {
        String relative = relativePath.toString().replace('\\', '/');
        if (relative.equals("oracle-logs") || relative.startsWith("oracle-logs/")) return true;
        for (Path part : relativePath) {
            if ("__pycache__".equals(part.toString())) return true;
        }
        return relative.endsWith(".pyc");
    }

    private void addIfExists(ZipOutputStream zip, Path path, String name) throws IOException {
        if (Files.isRegularFile(path)) addFile(zip, path, name);
    }

    private void addFile(ZipOutputStream zip, Path path, String name) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(path, zip);
        zip.closeEntry();
    }

    private Path claudeYamlPath() {
        return Path.of(properties.getFrameworkRoot()).resolve("configs/agents/claude_code.yaml");
    }

    private Path secretEnvPath() {
        return Path.of(properties.getFrameworkRoot()).resolve("secret/.env");
    }

    private long mtime(Path path) {
        try {
            if (Files.exists(path.resolve("run.json"))) return Files.getLastModifiedTime(path.resolve("run.json")).toMillis();
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private boolean isAleRunDirForTask(Path path, String taskId) {
        String slug = taskSlug(taskId);
        for (Path part : path) {
            if (slug.equals(part.toString())) return true;
        }
        return path.toString().contains(slug);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String defaultString(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static String unquote(String value) {
        if (value == null) return null;
        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\""))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String maskSecret(String value) {
        if (!hasText(value)) return "";
        String trimmed = value.trim();
        if (trimmed.length() <= 10) return "***";
        return trimmed.substring(0, 6) + "..." + trimmed.substring(trimmed.length() - 4);
    }

    private static String compact(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "..." : oneLine;
    }

    private static String compactReview(String value) {
        if (value == null) return "";
        String oneLine = value.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 320 ? oneLine.substring(0, 320) + "..." : oneLine;
    }

    private static String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (hasText(value)) return value;
        }
        return null;
    }
}
