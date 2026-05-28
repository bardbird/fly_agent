package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * SWE-Pro pipeline artifact view.
 */
@Data
public class SweArtifactDTO {

    private Long id;
    private Long runId;
    private String artifactType;
    private String artifactName;
    private String artifactPath;
    private Long fileSize;
    private String checksum;
    private LocalDateTime createdAt;
}
