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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
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
    private final AleExecutionGateway gateway;
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
        if (run == null || !StringUtils.hasText(run.getOutputRoot())) {
            return List.of();
        }
        return gateway.tailLog(Path.of(run.getOutputRoot()), "stage2", lines);
    }

    // ── internal ──────────────────────────────────────────────────────────────

    private void executeStage2(Long runId) {
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
            s2.put("model", "claude-sonnet-4-6");
            s2.put("timeout", 7200);
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
