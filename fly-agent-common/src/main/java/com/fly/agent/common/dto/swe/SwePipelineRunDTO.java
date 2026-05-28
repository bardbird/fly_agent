package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SWE-Pro pipeline run view.
 */
@Data
public class SwePipelineRunDTO {

    private Long id;
    private Long taskId;
    private Long candidateId;
    private String status;
    private String currentStage;
    private String workspacePath;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SweStageDTO> stages = new ArrayList<>();
    private List<SweArtifactDTO> artifacts = new ArrayList<>();
}
