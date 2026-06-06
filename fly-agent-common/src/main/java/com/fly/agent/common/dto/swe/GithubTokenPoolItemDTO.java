package com.fly.agent.common.dto.swe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Masked GitHub token pool item for the SWE-Pro UI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubTokenPoolItemDTO {

    private String id;

    private String maskedToken;

    private Boolean enabled;

    private Boolean available;

    private Boolean inUse;

    private Boolean unavailableToday;

    private String inUseBy;

    private String leasedUntil;

    private String lastUsedAt;

    private String unavailableAt;

    private String unavailableReason;

    private String updatedAt;
}
