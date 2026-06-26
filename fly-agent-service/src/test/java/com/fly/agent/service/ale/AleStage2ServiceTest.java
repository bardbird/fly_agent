package com.fly.agent.service.ale;

import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.entity.ale.AleTaskEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import com.fly.agent.common.dto.ale.AleStage2ReviewDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AleStage2ServiceTest {

    @Test
    void startStage2DispatchesGatewayWithStage2Payload() {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleProperties props = new AleProperties();

        AleRunEntity run = new AleRunEntity();
        run.setId(7L);
        run.setStatus("COMPLETED");
        run.setStage2Status(null);
        run.setOutputRoot(System.getProperty("java.io.tmpdir") + "/ale-s2-" + System.nanoTime());
        when(runMapper.selectById(7L)).thenReturn(run);
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of());
        when(gateway.dispatchAndWait(eq(7L), any(), any(), any()))
                .thenReturn(new AleExecutionGateway.StageResult("done", "ok"));

        AleStage2Service svc = new AleStage2Service(runMapper, taskMapper, gateway, props);
        svc.startStage2(7L);

        verify(gateway, timeout(2000)).dispatchAndWait(eq(7L), any(), any(), any());
    }

    @Test
    void startStage2RejectsWhenStage1NotCompleted() {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleRunEntity run = new AleRunEntity();
        run.setStatus("RUNNING");
        when(runMapper.selectById(1L)).thenReturn(run);

        AleStage2Service svc = new AleStage2Service(runMapper, taskMapper, gateway, new AleProperties());
        assertThrows(IllegalStateException.class, () -> svc.startStage2(1L));
        verifyNoInteractions(gateway);
    }

    @Test
    void reviewStage2SummarizesLowScoreArtifacts() throws Exception {
        Path runDir = Files.createTempDirectory("ale-stage2-review-");
        Files.createDirectories(runDir.resolve("results/domain__task_a"));
        Files.writeString(runDir.resolve("results/domain__task_a/result.json"),
                "{\"task_id\":\"domain/task_a\",\"status\":\"completed\",\"score\":0.7,\"error\":null}");
        Path aleDir = runDir.resolve("logs/ale/exp/claude_code/model/domain__task_a/v0/20260101_000000");
        Files.createDirectories(aleDir);
        Files.writeString(aleDir.resolve("run.json"), "{\"status\":\"completed\",\"score\":0.7}");
        Files.writeString(aleDir.resolve("eval_result.json"), "{\"score\":0.7,\"errors\":[\"schema mismatch\"]}");
        Files.writeString(runDir.resolve("stage2.log"),
                "INFO evaluation report: {\"checks\":{\"schema\":false},\"score\":0.7}\n");

        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleRunEntity run = new AleRunEntity();
        run.setId(9L);
        run.setRunKey("review-run");
        run.setStage2Status("COMPLETED");
        run.setOutputRoot(runDir.toString());
        AleTaskEntity task = new AleTaskEntity();
        task.setTaskId("domain/task_a");
        task.setStage2Status("completed");
        task.setStage2Score(BigDecimal.valueOf(0.7));

        when(runMapper.selectById(9L)).thenReturn(run);
        when(taskMapper.selectList(any())).thenReturn(List.of(task));

        AleStage2Service svc = new AleStage2Service(runMapper, taskMapper, gateway, new AleProperties());
        AleStage2ReviewDTO review = svc.reviewStage2(9L);

        assertTrue(review.getNeedsAttention());
        assertEquals(BigDecimal.valueOf(0.700).setScale(3), review.getAverageScore());
        assertFalse(review.getLikelyCauses().isEmpty());
        assertTrue(review.getTasks().get(0).getEvidence().stream().anyMatch(s -> s.contains("evaluation report")));
    }

    @Test
    void buildTaskArtifactsZipIncludesStage1TaskPackage() throws Exception {
        Path runDir = Files.createTempDirectory("ale-stage2-artifacts-");
        Files.createDirectories(runDir.resolve("tasks/domain/task_a"));
        Files.writeString(runDir.resolve("summary.json"), "{\"run\":\"stage1\"}");
        Files.writeString(runDir.resolve("stage1.log"), "oracle validation log\n");
        Files.writeString(runDir.resolve("stage1_progress.json"), "{\"phase\":\"oracle\"}");
        Files.writeString(runDir.resolve("request.json"), "{\"request\":true}");
        Files.writeString(runDir.resolve("plan.json"), "{\"plan\":true}");
        Files.writeString(runDir.resolve("dry_run_agent.yaml"), "agent: dry-run\n");
        Files.writeString(runDir.resolve("dry_run_environment.yaml"), "environment: dry-run\n");
        Files.writeString(runDir.resolve("dry_run_experiment.yaml"), "experiment: dry-run\n");
        Files.writeString(runDir.resolve("tasks/domain/task_a/task_card.json"), "{\"task\":\"card\"}");
        Files.writeString(runDir.resolve("tasks/domain/task_a/main.py"), "def evaluate(): pass\n");
        Files.createDirectories(runDir.resolve("tasks/domain/task_a/oracle-logs"));
        Files.writeString(runDir.resolve("tasks/domain/task_a/oracle-logs/oracle-evidence.json"), "{\"status\":\"verified\"}");
        Files.createDirectories(runDir.resolve("tasks/domain/task_a/__pycache__"));
        Files.writeString(runDir.resolve("tasks/domain/task_a/__pycache__/main.cpython-312.pyc"), "bytecode");
        Files.createDirectories(runDir.resolve("results/domain__task_a"));
        Files.writeString(runDir.resolve("results/domain__task_a/result.json"),
                "{\"task_id\":\"domain/task_a\",\"status\":\"failed\",\"score\":0.0}");
        Path rawAleDir = runDir.resolve("logs/ale/ale_stage2_demo/claude_code/model/domain__task_a/v0/20260623_161419");
        Files.createDirectories(rawAleDir.resolve("origin_log/claude-code"));
        Files.createDirectories(rawAleDir.resolve("output"));
        Files.writeString(rawAleDir.resolve("run.json"), "{\"status\":\"failed\",\"score\":0.0}");
        Files.writeString(rawAleDir.resolve("eval_result.json"), "{\"score\":0.0}");
        Files.writeString(rawAleDir.resolve("events.jsonl"), "{\"event\":\"started\"}\n");
        Files.writeString(rawAleDir.resolve("trajectory.json"), "{\"steps\":[]}");
        Files.writeString(rawAleDir.resolve("origin_log/claude-code/transcript.jsonl"), "{\"type\":\"result\"}\n");
        Files.writeString(rawAleDir.resolve("output/task_package.json"), "{\"answer\":true}");
        Path otherAleDir = runDir.resolve("logs/ale/ale_stage2_demo/claude_code/model/domain__task_b/v0/20260623_161420");
        Files.createDirectories(otherAleDir);
        Files.writeString(otherAleDir.resolve("run.json"), "{\"status\":\"completed\",\"score\":1.0}");

        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleRunEntity run = new AleRunEntity();
        run.setId(10L);
        run.setOutputRoot(runDir.toString());
        AleTaskEntity task = new AleTaskEntity();
        task.setId(20L);
        task.setRunId(10L);
        task.setTaskId("domain/task_a");
        when(runMapper.selectById(10L)).thenReturn(run);
        when(taskMapper.selectById(20L)).thenReturn(task);

        AleStage2Service svc = new AleStage2Service(runMapper, taskMapper, mock(AleExecutionGateway.class), new AleProperties());
        byte[] zipBytes = svc.buildTaskArtifactsZip(10L, 20L);

        assertTrue(zipContains(zipBytes, "domain/task_a/task_card.json"));
        assertTrue(zipContains(zipBytes, "domain/task_a/main.py"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/summary.json"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/stage1.log"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/stage1_progress.json"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/request.json"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/plan.json"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/dry_run_agent.yaml"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/dry_run_environment.yaml"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/dry_run_experiment.yaml"));
        assertTrue(zipContains(zipBytes, "domain/task_a/oracle/oracle-logs/oracle-evidence.json"));
        assertTrue(zipContains(zipBytes, "domain/task_a/ale/result.json"));
        assertTrue(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161419/run.json"));
        assertTrue(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161419/eval_result.json"));
        assertTrue(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161419/events.jsonl"));
        assertTrue(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161419/trajectory.json"));
        assertTrue(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161419/origin_log/claude-code/transcript.jsonl"));
        assertTrue(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161419/output/task_package.json"));
        assertFalse(zipContains(zipBytes, "domain/task_a/oracle-logs/oracle-evidence.json"));
        assertFalse(zipContains(zipBytes, "domain/task_a/__pycache__/main.cpython-312.pyc"));
        assertFalse(zipContains(zipBytes, "oracle/summary.json"));
        assertFalse(zipContains(zipBytes, "ale/result.json"));
        assertFalse(zipContains(zipBytes,
                "domain/task_a/ale/runs/ale_stage2_demo/claude_code/model/domain__task_a/v0/20260623_161419/run.json"));
        assertFalse(zipContains(zipBytes,
                "domain/task_a/ale/runs/v0/20260623_161420/run.json"));
    }

    @Test
    void buildArtifactsZipIncludesAllRawAleRuns() throws Exception {
        Path runDir = Files.createTempDirectory("ale-stage2-run-artifacts-");
        Files.writeString(runDir.resolve("summary.json"), "{\"run\":\"stage1\"}");
        Files.createDirectories(runDir.resolve("results/domain__task_a"));
        Files.writeString(runDir.resolve("results/domain__task_a/result.json"), "{\"status\":\"completed\"}");
        Path taskA = runDir.resolve("logs/ale/ale_stage2_demo/claude_code/model/domain__task_a/v0/20260623_161419");
        Path taskB = runDir.resolve("logs/ale/ale_stage2_demo/claude_code/model/domain__task_b/v0/20260623_161420");
        Files.createDirectories(taskA);
        Files.createDirectories(taskB);
        Files.writeString(taskA.resolve("run.json"), "{\"task\":\"a\"}");
        Files.writeString(taskA.resolve("trajectory.json"), "{\"steps\":[\"a\"]}");
        Files.writeString(taskB.resolve("run.json"), "{\"task\":\"b\"}");
        Files.writeString(taskB.resolve("events.jsonl"), "{\"event\":\"b\"}\n");

        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleRunEntity run = new AleRunEntity();
        run.setId(11L);
        run.setOutputRoot(runDir.toString());
        when(runMapper.selectById(11L)).thenReturn(run);

        AleStage2Service svc = new AleStage2Service(runMapper, mock(AleTaskMapper.class), mock(AleExecutionGateway.class), new AleProperties());
        byte[] zipBytes = svc.buildArtifactsZip(11L);

        assertTrue(zipContains(zipBytes,
                "stage2/logs/ale/ale_stage2_demo/claude_code/model/domain__task_a/v0/20260623_161419/run.json"));
        assertTrue(zipContains(zipBytes,
                "stage2/logs/ale/ale_stage2_demo/claude_code/model/domain__task_a/v0/20260623_161419/trajectory.json"));
        assertTrue(zipContains(zipBytes,
                "stage2/logs/ale/ale_stage2_demo/claude_code/model/domain__task_b/v0/20260623_161420/run.json"));
        assertTrue(zipContains(zipBytes,
                "stage2/logs/ale/ale_stage2_demo/claude_code/model/domain__task_b/v0/20260623_161420/events.jsonl"));
    }

    private boolean zipContains(byte[] zipBytes, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (name.equals(entry.getName())) return true;
            }
        }
        return false;
    }
}
