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
        Files.writeString(runDir.resolve("tasks/domain/task_a/task_card.json"), "{\"task\":\"card\"}");
        Files.createDirectories(runDir.resolve("results/domain__task_a"));
        Files.writeString(runDir.resolve("results/domain__task_a/result.json"),
                "{\"task_id\":\"domain/task_a\",\"status\":\"failed\",\"score\":0.0}");

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

        assertTrue(zipContains(zipBytes, "stage1/summary.json"));
        assertTrue(zipContains(zipBytes, "stage1/tasks/domain/task_a/task_card.json"));
        assertTrue(zipContains(zipBytes, "stage2/results/domain__task_a/result.json"));
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
