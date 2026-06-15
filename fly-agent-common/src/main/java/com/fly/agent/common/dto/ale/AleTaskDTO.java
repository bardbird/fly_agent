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
    private BigDecimal score;
    private String taskDir;
    private String evidencePath;
    private String summary;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
