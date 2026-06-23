package com.fly.agent.service.ale;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlePropertiesTest {
    @Test
    void defaultsAreProductionAbsolutePathsAndLists() {
        AleProperties p = new AleProperties();
        assertEquals("/data/fly-agent/ale-runs", p.getOutputRoot());
        assertEquals("/home/ubuntu/agents-last-exam", p.getFrameworkRoot());
        assertEquals("/data/fly-agent/ale-runs/.queue", p.getQueueDir());
        assertEquals(90, p.getStage1TimeoutMinutes());
        assertEquals(240, p.getStage2TimeoutMinutes());
        assertTrue(p.getCodexModels().contains("gpt-5.5"));
    }
}
