package com.fly.agent.common.enums.swe;

import lombok.Getter;

/**
 * Ordered stages for SWE-Pro data production.
 */
@Getter
public enum SwePipelineStage {

    ENVIRONMENT_CHECK(5, "ENVIRONMENT_CHECK", "Environment check"),
    SOURCE_INGEST(10, "SOURCE_INGEST", "Source ingest"),
    CANDIDATE_DEDUP_REGISTER(20, "CANDIDATE_DEDUP_REGISTER", "Candidate dedup and registry"),
    TASK_PACKAGE_INIT(30, "TASK_PACKAGE_INIT", "Task package initialization"),
    PATCH_VERIFY(40, "PATCH_VERIFY", "Patch verification"),
    HARNESS_BUILD(50, "HARNESS_BUILD", "Harness build"),
    LOCAL_VERIFY(60, "LOCAL_VERIFY", "Local verification"),
    MODEL_QWEN_EVAL(70, "MODEL_QWEN_EVAL", "Qwen model evaluation"),
    MODEL_OPUS_EVAL(80, "MODEL_OPUS_EVAL", "Opus model evaluation"),
    DOCKER_PACKAGE(90, "DOCKER_PACKAGE", "Docker package"),
    QC_REVIEW(100, "QC_REVIEW", "Quality review"),
    PACKAGE_EXPORT(110, "PACKAGE_EXPORT", "Package export");

    private final int sortOrder;
    private final String code;
    private final String description;

    SwePipelineStage(int sortOrder, String code, String description) {
        this.sortOrder = sortOrder;
        this.code = code;
        this.description = description;
    }

    public static SwePipelineStage fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SwePipelineStage stage : values()) {
            if (stage.code.equalsIgnoreCase(code) || stage.name().equalsIgnoreCase(code)) {
                return stage;
            }
        }
        return null;
    }
}
