package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Model I/O artifacts for one SWE-agent attempt.
 */
@Data
public class SweModelIoAttemptDTO {

    private String evaluationName;
    private Integer attempt;
    private String runDir;
    private String status;
    private String error;
    private String rawResponsePath;
    private Integer rawResponseLines;
    private Long rawResponseBytes;
    private String sweAgentOutputPath;
    private Long sweAgentOutputBytes;
    private String sweAgentOutputTail;
    private List<String> modelInputBlocks = new ArrayList<>();
    private List<SweModelIoResponseDTO> responses = new ArrayList<>();
}
