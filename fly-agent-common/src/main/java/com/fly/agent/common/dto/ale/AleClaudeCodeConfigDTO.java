package com.fly.agent.common.dto.ale;

import lombok.Data;

@Data
public class AleClaudeCodeConfigDTO {

    private String model;
    private String provider;
    private String baseUrl;
    private String cliVersion;
    private Integer maxThinkingTokens;
    private Boolean apiKeySet;
    private Boolean authTokenSet;
    private String apiKeyPreview;
    private String authTokenPreview;
}
