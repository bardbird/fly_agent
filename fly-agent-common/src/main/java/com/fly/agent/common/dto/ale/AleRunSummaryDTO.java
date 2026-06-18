package com.fly.agent.common.dto.ale;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class AleRunSummaryDTO {

    private Long runId;
    private String runKey;
    private String domain;
    private String discipline;
    private String scenario;
    private String difficulty;
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
    private Map<String, Long> domainStats = new LinkedHashMap<>();
}
