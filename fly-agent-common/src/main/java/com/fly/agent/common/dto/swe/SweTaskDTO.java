package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SWE-Pro task view.
 */
@Data
public class SweTaskDTO {

    private Long id;
    private Long candidateId;
    private String taskName;
    private String repo;
    private String sourceUrl;
    private String baseCommit;
    private String fixCommit;
    private String repoLanguage;
    private String issueSpecificity;
    private String issueCategories;
    private String samplePath;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<SwePipelineRunDTO> recentRuns = new ArrayList<>();
}
