package com.fly.agent.service.swe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * SWE-Pro production configuration. Secrets are read from the active YAML file
 * or environment and are never written to artifacts.
 */
@Data
@Component
@ConfigurationProperties(prefix = "swe")
public class SweProperties {

    private String toolkitRoot = "tools/swe-pro-production";

    private String productionRoot = "swe-output";

    private String python = "python3";

    private Integer qwenAttempts = 4;

    private Integer opusAttempts = 8;

    private String opusMaxStepsSchedule = "180,10";

    private Integer modelTimeoutSeconds = 3600;

    private SweAgent sweAgent = new SweAgent();

    private Github github = new Github();

    private Model qwen = new Model();

    private Model opus = new Model();

    @Data
    public static class Github {
        private String token;
    }

    @Data
    public static class SweAgent {
        private String root = "tools/SWE-agent";
        private Integer maxSteps = 20;
    }

    @Data
    public static class Model {
        private String baseUrl;
        private String token;
        private String model;
        private String provider = "openai";
        private Integer maxTokens = 4096;
        private Integer maxInputTokens = 22000;
        private Double temperature = 0.7;
    }
}
