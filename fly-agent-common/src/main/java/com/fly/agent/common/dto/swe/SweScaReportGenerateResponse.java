package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of task-level SCA report generation.
 */
@Data
public class SweScaReportGenerateResponse {

    private String packagePath;

    private String outputDir;

    private String summary;

    private List<String> generatedFiles = new ArrayList<>();
}
