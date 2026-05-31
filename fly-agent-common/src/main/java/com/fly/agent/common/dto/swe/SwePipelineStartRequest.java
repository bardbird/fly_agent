package com.fly.agent.common.dto.swe;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request for starting a SWE-Pro pipeline run.
 */
@Data
public class SwePipelineStartRequest {

    @NotNull(message = "taskId不能为空")
    private Long taskId;

    /**
     * Optional existing run id. When provided, completed stages are skipped
     * and execution continues on the same run record.
     */
    private Long resumeRunId;

    /**
     * Optional stage code to replay when resumeRunId is provided. The selected
     * stage and later stages are reset before the existing resume flow runs.
     */
    private String resumeFromStage;

    /**
     * Explicitly replay a run that is still marked RUNNING. This is intended
     * for operator recovery after the service process was restarted and the
     * in-memory async worker was lost.
     */
    private Boolean forceResume;

    /**
     * Optional package path. If omitted, the task samplePath is used.
     */
    private String samplePath;

    /**
     * Optional workspace root reserved for future clone/build execution.
     */
    private String workspacePath;
}
