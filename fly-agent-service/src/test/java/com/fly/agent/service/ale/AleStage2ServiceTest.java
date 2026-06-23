package com.fly.agent.service.ale;

import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
