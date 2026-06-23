package com.fly.agent.service.ale;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ale")
public class AleProperties {

    private String codexBinary = "codex";
    private String outputRoot = "/data/fly-agent/ale-runs";
    private String frameworkRoot = "/home/ubuntu/agents-last-exam";
    private String queueDir = "/data/fly-agent/ale-runs/.queue";
    private List<String> codexModels = List.of("gpt-5.5", "gpt-5-mini", "gpt-5-codex");
    private int stage1TimeoutMinutes = 90;
    private int stage2TimeoutMinutes = 240;
}
