package com.fly.agent.service.ale;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ale")
public class AleProperties {

    private String codexBinary = "codex";
    private String outputRoot = "ale-runs";
    private String frameworkRoot = "/Users/liuyifei/Liu/github/agents-last-exam";
}
