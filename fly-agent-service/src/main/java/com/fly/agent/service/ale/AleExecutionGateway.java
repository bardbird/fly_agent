package com.fly.agent.service.ale;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

@Slf4j
@Service
@RequiredArgsConstructor
public class AleExecutionGateway {

    private final AleProperties properties;

    public record StageResult(String phase, String message) {
        public boolean isDone() { return "done".equals(phase); }
        public boolean isFailed() { return "failed".equals(phase); }
    }

    public StageResult dispatchAndWait(Long runId, Path runDir, Map<String, Object> payload,
                                       IntConsumer onProgress) {
        String type = (String) payload.get("type");
        Path progressFile = runDir.resolve(type + "_progress.json");

        try {
            writeProgress(progressFile, type, "starting", 0, null);

            Path queueDir = Path.of(properties.getQueueDir());
            Files.createDirectories(queueDir);
            Path tmp = queueDir.resolve(runId + ".json.tmp");
            Path trigger = queueDir.resolve(runId + ".json");
            Files.writeString(tmp, JSON.toJSONString(payload), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, trigger, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.info("ALE dispatch: type={} runId={} trigger={}", type, runId, trigger);

            int timeoutMinutes = "stage2".equals(type)
                    ? properties.getStage2TimeoutMinutes() : properties.getStage1TimeoutMinutes();
            long deadline = System.currentTimeMillis() + Math.max(0, timeoutMinutes) * 60_000L;
            int lastPercent = -1;
            while (true) {
                JSONObject frame = readProgress(progressFile);
                if (frame != null) {
                    Integer percent = frame.getInteger("percent");
                    if (percent != null && percent > lastPercent) {
                        lastPercent = percent;
                        onProgress.accept(percent);
                    }
                    String phase = frame.getString("phase");
                    if ("done".equals(phase)) return new StageResult("done", frame.getString("message"));
                    if ("failed".equals(phase)) return new StageResult("failed", frame.getString("message"));
                }
                if (System.currentTimeMillis() >= deadline) {
                    return new StageResult("failed", "timeout after " + timeoutMinutes + "min");
                }
                Thread.sleep(3000);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new StageResult("failed", "dispatch interrupted");
        } catch (Exception e) {
            log.error("ALE dispatch failed", e);
            return new StageResult("failed", e.getMessage());
        }
    }

    public List<String> tailLog(Path runDir, String stage, int lines) {
        Path logPath = runDir.resolve(stage + ".log");
        if (!Files.exists(logPath)) return List.of();
        try {
            List<String> all = Files.readAllLines(logPath, StandardCharsets.UTF_8);
            int from = Math.max(0, all.size() - Math.max(lines, 1));
            return new ArrayList<>(all.subList(from, all.size()));
        } catch (Exception e) {
            return List.of("failed to read log: " + e.getMessage());
        }
    }

    private void writeProgress(Path path, String stage, String phase, int percent, String message) throws Exception {
        JSONObject o = new JSONObject();
        o.put("stage", stage);
        o.put("phase", phase);
        o.put("percent", percent);
        if (message != null) o.put("message", message);
        Files.writeString(path, JSON.toJSONString(o), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private JSONObject readProgress(Path path) {
        try {
            if (!Files.exists(path)) return null;
            return JSON.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }
}
