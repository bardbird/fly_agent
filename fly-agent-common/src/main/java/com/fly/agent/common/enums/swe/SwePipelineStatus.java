package com.fly.agent.common.enums.swe;

import lombok.Getter;

/**
 * SWE-Pro pipeline execution status.
 */
@Getter
public enum SwePipelineStatus {

    CREATED("CREATED", "Created"),
    RUNNING("RUNNING", "Running"),
    COMPLETED("COMPLETED", "Completed"),
    FAILED("FAILED", "Failed"),
    SKIPPED("SKIPPED", "Skipped"),
    DELIVERED("DELIVERED", "Delivered");

    private final String code;
    private final String description;

    SwePipelineStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
