package com.fly.agent.service.ale;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AleExecutionGatewayTest {

    @TempDir
    Path tempDir;
    private AleProperties props;
    private AleExecutionGateway gateway;

    @BeforeEach
    void setUp() {
        props = new AleProperties();
        props.setQueueDir(tempDir.resolve("queue").toString());
        props.setStage1TimeoutMinutes(90);
        props.setStage2TimeoutMinutes(240);
        gateway = new AleExecutionGateway(props);
    }

    @Test
    void dispatchWritesTriggerAtomicallyAndResetsProgress() throws Exception {
        Path runDir = tempDir.resolve("run-1");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("stage2_progress.json"),
                "{\"stage\":\"stage2\",\"phase\":\"done\",\"percent\":100}");

        Map<String, Object> payload = Map.of(
                "type", "stage2", "run_id", 1, "run_dir", runDir.toString(),
                "stage2", Map.of("framework_root", "/fw"));

        AtomicInteger observedPercent = new AtomicInteger(-1);
        CountDownLatch triggerWritten = new CountDownLatch(1);

        Thread daemon = new Thread(() -> {
            try {
                Path trigger = tempDir.resolve("queue").resolve("1.json");
                for (int i = 0; i < 200 && !Files.exists(trigger); i++) Thread.sleep(25);
                triggerWritten.countDown();
                String prog = Files.readString(runDir.resolve("stage2_progress.json"));
                assertTrue(prog.contains("\"starting\""), "progress should be reset to starting");
                Files.writeString(runDir.resolve("stage2_progress.json"),
                        "{\"stage\":\"stage2\",\"phase\":\"failed\",\"percent\":100,\"message\":\"boom\"}");
            } catch (Exception ignored) {}
        });
        daemon.setDaemon(true);
        daemon.start();

        AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                1L, runDir, payload, observedPercent::set);

        assertTrue(triggerWritten.await(5, TimeUnit.SECONDS));
        assertEquals("failed", result.phase());
        assertEquals("boom", result.message());

        Path trigger = tempDir.resolve("queue").resolve("1.json");
        assertTrue(Files.exists(trigger));
        assertFalse(Files.exists(tempDir.resolve("queue").resolve("1.json.tmp")));
        JSONObject parsed = JSON.parseObject(Files.readString(trigger));
        assertEquals("stage2", parsed.getString("type"));
    }

    @Test
    void dispatchReturnsDoneWhenPhaseDone() throws Exception {
        Path runDir = tempDir.resolve("run-2");
        Files.createDirectories(runDir);
        Map<String, Object> payload = Map.of(
                "type", "stage1", "run_id", 2, "run_dir", runDir.toString(),
                "stage1", Map.of("framework_root", "/fw"));
        Thread daemon = new Thread(() -> {
            try {
                Path trigger = tempDir.resolve("queue").resolve("2.json");
                for (int i = 0; i < 200 && !Files.exists(trigger); i++) Thread.sleep(25);
                Thread.sleep(50);
                Files.writeString(runDir.resolve("stage1_progress.json"),
                        "{\"stage\":\"stage1\",\"phase\":\"done\",\"percent\":100}");
            } catch (Exception ignored) {}
        });
        daemon.setDaemon(true);
        daemon.start();

        AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                2L, runDir, payload, p -> {});
        assertEquals("done", result.phase());
    }

    @Test
    void dispatchTimesOutWhenNoTerminalPhase() throws Exception {
        Path runDir = tempDir.resolve("run-3");
        Files.createDirectories(runDir);
        props.setStage1TimeoutMinutes(0);
        Map<String, Object> payload = Map.of(
                "type", "stage1", "run_id", 3, "run_dir", runDir.toString(),
                "stage1", Map.of("framework_root", "/fw"));
        AleExecutionGateway.StageResult result = gateway.dispatchAndWait(
                3L, runDir, payload, p -> {});
        assertEquals("failed", result.phase());
        assertTrue(result.message().contains("timeout"));
    }

    @Test
    void tailLogReturnsLastNLines() throws Exception {
        Path runDir = tempDir.resolve("run-4");
        Files.createDirectories(runDir);
        Files.writeString(runDir.resolve("stage1.log"),
                "line1\nline2\nline3\nline4\n", StandardCharsets.UTF_8);
        List<String> tail = gateway.tailLog(runDir, "stage1", 2);
        assertEquals(List.of("line3", "line4"), tail);
    }
}
