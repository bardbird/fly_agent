package com.fly.agent.common.dto.swe;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for creating a SWE-Pro production task.
 */
@Data
public class SweTaskCreateRequest {

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    private Long candidateId;

    private String repo;

    private String sourceUrl;

    private String baseCommit;

    private String fixCommit;

    private String repoLanguage;

    private String issueSpecificity;

    private String issueCategories;

    private String samplePath;
}
