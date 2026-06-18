package com.fly.agent.common.dto.ale;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AleRunDTO {

    private Long id;
    private String runKey;
    private String domain;
    private String discipline;
    private String scenario;
    private String difficulty;
    private String inputMode;
    private String outputMode;
    private String verificationMode;
    private String referenceStrategy;
    private Integer targetCount;
    private String codexModel;
    private String status;
    private String stage2Status;
    private Integer stage2Progress;
    private Integer progressPercent;
    private Integer totalTasks;
    private Integer completedTasks;
    private Integer failedTasks;
    private Integer blockedTasks;
    private String outputRoot;
    private String logPath;
    private String summaryPath;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime stage2StartedAt;
    private LocalDateTime stage2FinishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<AleTaskDTO> tasks = new ArrayList<>();
    private Map<String, Long> domainStats;
}
