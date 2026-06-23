package com.fly.agent.common.dto.ale;

import lombok.Data;

@Data
public class AleClaudeCodeConfigRequest {

    private String model;
    private String provider;
    private String baseUrl;
    private String cliVersion;
    private Integer maxThinkingTokens;
    private String apiKey;
    private String authToken;
}
