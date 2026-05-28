package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Live model input/output evidence for a SWE-Pro pipeline run.
 */
@Data
public class SweModelIoConsoleDTO {

    private Long runId;
    private Long taskId;
    private String packagePath;
    private String problemStatementPath;
    private String problemStatement;
    private String guardConfigPath;
    private String guardConfig;
    private List<SweModelIoAttemptDTO> attempts = new ArrayList<>();
}
