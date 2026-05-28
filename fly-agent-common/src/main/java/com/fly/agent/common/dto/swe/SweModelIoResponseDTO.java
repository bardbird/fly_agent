package com.fly.agent.common.dto.swe;

import lombok.Data;

/**
 * One raw model API response captured by SWE-agent.
 */
@Data
public class SweModelIoResponseDTO {

    private Integer apiCallIndex;
    private Double timestamp;
    private String configuredModel;
    private String provider;
    private String responseId;
    private String responseModel;
    private String finishReason;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String assistantContent;
    private String rawJson;
}
