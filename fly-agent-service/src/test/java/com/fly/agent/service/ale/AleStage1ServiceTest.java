package com.fly.agent.service.ale;

import com.fly.agent.common.dto.ale.AleRunRequest;
import com.fly.agent.dao.entity.ale.AleRunEntity;
import com.fly.agent.dao.mapper.ale.AleRunMapper;
import com.fly.agent.dao.mapper.ale.AleTaskMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AleStage1ServiceTest {

    private AleRunRequest request() {
        AleRunRequest r = new AleRunRequest();
        r.setDomain("computing_math");
        r.setDiscipline("software-engineering");
        r.setScenario("task-authoring");
        r.setDifficulty("easy");
        r.setInputMode("brief");
        r.setOutputMode("task-package");
        r.setVerificationMode("oracle");
        r.setReferenceStrategy("hidden-reference");
        r.setTargetCount(1);
        r.setCodexModel("gpt-5.5");
        return r;
    }

    @Test
    void startRunDispatchesGatewayWithExactTasksContract() {
        AleRunMapper runMapper = mock(AleRunMapper.class);
        AleTaskMapper taskMapper = mock(AleTaskMapper.class);
        AleExecutionGateway gateway = mock(AleExecutionGateway.class);
        AleProperties props = new AleProperties();
        props.setOutputRoot(System.getProperty("java.io.tmpdir") + "/ale-test-" + System.nanoTime());

        when(runMapper.insert(any())).thenAnswer(inv -> {
            ((AleRunEntity) inv.getArgument(0)).setId(42L);
            return 1;
        });
        when(runMapper.selectById(42L)).thenReturn(new AleRunEntity());
        when(taskMapper.selectList(any())).thenReturn(java.util.List.of());
        when(gateway.dispatchAndWait(eq(42L), any(), any(), any()))
                .thenReturn(new AleExecutionGateway.StageResult("done", "ok"));

        AleStage1Service svc = new AleStage1Service(runMapper, taskMapper, gateway, props);
        svc.startRun(request());

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gateway, timeout(2000)).dispatchAndWait(eq(42L), any(), payloadCaptor.capture(), any());
        Map<String, Object> payload = payloadCaptor.getValue();
        assertEquals("stage1", payload.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> stage1 = (Map<String, Object>) payload.get("stage1");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) stage1.get("tasks");
        assertEquals(1, tasks.size());
        assertEquals("computing_math/task_authoring_01", tasks.get(0).get("task_id"));
    }
}
