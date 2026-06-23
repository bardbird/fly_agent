package com.fly.agent.common.dto.ale;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class AleStage2ReviewDTO {

    private Long runId;
    private String runKey;
    private String status;
    private BigDecimal averageScore;
    private Boolean needsAttention;
    private String analysisSource;
    private String summary;
    private List<String> likelyCauses = new ArrayList<>();
    private List<String> suggestedFixes = new ArrayList<>();
    private List<AleStage2TaskReviewDTO> tasks = new ArrayList<>();
    private String artifactHint;
}
