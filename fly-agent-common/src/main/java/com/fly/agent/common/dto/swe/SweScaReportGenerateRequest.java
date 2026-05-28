package com.fly.agent.common.dto.swe;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for generating task-level SCA delivery materials from an existing
 * SWE-Pro task package.
 */
@Data
public class SweScaReportGenerateRequest {

    @NotBlank(message = "packagePath不能为空")
    private String packagePath;

    /**
     * Optional raw SPDX JSON scan output for manual re-indexing or tests. When
     * omitted, the generator runs syft or trivy against packagePath/repo.
     */
    private String rawScanPath;

    private String scannerName;

    /**
     * Optional destination directory. When omitted, output is written under
     * packagePath/SCA_交付材料.
     */
    private String outputDir;

    /**
     * Generate a manifest-derived delivery package from task.json, repo
     * manifests, Dockerfile, and LICENSE/NOTICE files without reading any
     * previously generated delivery workbook or report.
     */
    private Boolean manifestOnly;
}
