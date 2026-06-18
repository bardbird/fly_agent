package com.fly.agent.service.ale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.common.dto.ale.AleRunDTO;
import com.fly.agent.common.dto.ale.AleRunSummaryDTO;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AleStage2Service {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private final AleRunMapper runMapper;
    private final AleTaskMapper taskMapper;
    private final AleProperties properties;

    /** Start stage-2 execution for a completed stage-1 run. */
    public AleRunDTO startStage2(Long runId) {
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

        // Mark stage2 as running
        AleRunEntity update = new AleRunEntity();
        update.setId(runId);
        update.setStage2Status("RUNNING");
        update.setStage2Progress(0);
        update.setStage2StartedAt(LocalDateTime.now());
        runMapper.updateById(update);

        EXECUTOR.submit(() -> executeStage2(runId));
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

    // ── internal ──────────────────────────────────────────────────────────────

    private void executeStage2(Long runId) {
        AleRunEntity run = runMapper.selectById(runId);
        if (run == null) return;
        Path runDir = Path.of(run.getOutputRoot());
        Path frameworkRoot = Path.of(properties.getFrameworkRoot());

        try {
            // Build command
            String runnerScript = findRunnerScript();
            List<String> command = new ArrayList<>();
            command.add("python3");
            command.add(runnerScript);
            command.add("--run-dir");
            command.add(runDir.toAbsolutePath().toString());
            command.add("--framework-root");
            command.add(frameworkRoot.toAbsolutePath().toString());

            log.info("Starting stage2: {}", String.join(" ", command));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(Path.of(".").toAbsolutePath().toFile());
            builder.redirectErrorStream(true);

            Path stage2Log = runDir.resolve("stage2.log");
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(stage2Log.toFile()));

            Process process = builder.start();
            process.getOutputStream().close();

            // Poll progress
            int lastProgress = 0;
            updateStage2Progress(runId, lastProgress);
            while (process.isAlive()) {
                Thread.sleep(5000);
                int progress = estimateStage2Progress(runDir);
                if (progress > lastProgress) {
                    lastProgress = progress;
                    updateStage2Progress(runId, lastProgress);
                }
            }

            int exitCode = process.waitFor();
            log.info("Stage2 finished, exit={}", exitCode);

            // Collect results from summary.json
            Path summaryPath = runDir.resolve("summary.json");
            if (Files.exists(summaryPath)) {
                parseAndApplyResults(runId, runDir, summaryPath);
            }

            // Update run status
            AleRunEntity update = new AleRunEntity();
            update.setId(runId);
            update.setStage2Status(exitCode == 0 ? "COMPLETED" : "FAILED");
            update.setStage2Progress(100);
            update.setStage2FinishedAt(LocalDateTime.now());
            update.setStage2SummaryPath(summaryPath.toString());
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

    private String findRunnerScript() {
        // Look for the runner script relative to project root
        Path script = Path.of("tools/ale-task-factory/scripts/ale_stage2_runner.py");
        if (Files.exists(script)) return script.toAbsolutePath().toString();
        // Fallback: inside container, tools are at /app/tools
        script = Path.of("/app/tools/ale-task-factory/scripts/ale_stage2_runner.py");
        if (Files.exists(script)) return script.toAbsolutePath().toString();
        return "tools/ale-task-factory/scripts/ale_stage2_runner.py";
    }

    private int estimateStage2Progress(Path runDir) {
        int progress = 5;
        Path resultsDir = runDir.resolve("results");
        if (Files.exists(resultsDir)) {
            progress = 20;
            try {
                long count = Files.list(resultsDir)
                        .filter(Files::isDirectory)
                        .count();
                progress = Math.max(progress, 20 + (int) (count * 10));
            } catch (IOException ignored) {}
        }
        Path summaryPath = runDir.resolve("summary.json");
        try {
            if (Files.exists(summaryPath)) {
                String raw = Files.readString(summaryPath);
                if (raw.contains("\"avg_score\"")) {
                    progress = 100;
                }
            }
        } catch (IOException ignored) {}
        return Math.min(progress, 99);
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
                    update.setStage2Error(stringField(result, "error"));
                    taskMapper.updateById(update);
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
}
