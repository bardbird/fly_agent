package com.fly.agent.common.dto.ale;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
public class AleStage2TaskReviewDTO {

    private String taskId;
    private String status;
    private BigDecimal score;
    private String error;
    private Boolean needsAttention;
    private String summary;
    private List<String> evidence = new ArrayList<>();
    private List<String> suggestedFixes = new ArrayList<>();
}
