package com.fly.agent.common.dto.swe;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request for creating a production task from a persisted GitHub PR candidate.
 */
@Data
public class SweTaskFromCandidateRequest {

    @NotNull(message = "candidateId不能为空")
    private Long candidateId;

    private String taskName;

    private String workspacePath;
}
