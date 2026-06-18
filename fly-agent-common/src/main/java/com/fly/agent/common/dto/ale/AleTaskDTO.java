package com.fly.agent.common.dto.ale;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class AleTaskDTO {

    private Long id;
    private Long runId;
    private String taskId;
    private String title;
    private String domain;
    private String discipline;
    private String scenario;
    private String difficulty;
    private String status;
    private String stage2Status;
    private BigDecimal score;
    private BigDecimal stage2Score;
    private BigDecimal stage2DurationS;
    private String taskDir;
    private String evidencePath;
    private String stage2ResultDir;
    private String summary;
    private String errorMessage;
    private String stage2Error;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
