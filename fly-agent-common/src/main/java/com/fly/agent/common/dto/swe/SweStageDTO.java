package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * SWE-Pro pipeline stage view.
 */
@Data
public class SweStageDTO {

    private Long id;
    private Long runId;
    private String stageCode;
    private String stageName;
    private String status;
    private Integer sortOrder;
    private String resultSummary;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
