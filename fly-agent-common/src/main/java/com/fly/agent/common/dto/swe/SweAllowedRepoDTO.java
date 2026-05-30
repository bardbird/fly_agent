package com.fly.agent.common.dto.swe;

import lombok.Data;

/**
 * Repository accepted by the SCA license gate.
 */
@Data
public class SweAllowedRepoDTO {

    private Long id;

    private String repo;

    private String githubUrl;

    private String primaryLanguage;

    private Integer githubStars;

    private String licenseSpdxId;

    private String licenseName;

    private String compatibilityReason;

    private Boolean inCandidate;

    private String checkedAt;

    private String candidateLastScannedAt;
}
