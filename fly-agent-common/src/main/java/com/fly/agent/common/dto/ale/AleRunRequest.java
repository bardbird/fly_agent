package com.fly.agent.common.dto.ale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AleRunRequest {

    @NotBlank(message = "domain不能为空")
    private String domain;

    @NotBlank(message = "discipline不能为空")
    private String discipline;

    @NotBlank(message = "scenario不能为空")
    private String scenario;

    @NotBlank(message = "difficulty不能为空")
    private String difficulty;

    @NotBlank(message = "inputMode不能为空")
    private String inputMode;

    @NotBlank(message = "outputMode不能为空")
    private String outputMode;

    @NotBlank(message = "verificationMode不能为空")
    private String verificationMode;

    @NotBlank(message = "referenceStrategy不能为空")
    private String referenceStrategy;

    @NotNull(message = "targetCount不能为空")
    private Integer targetCount;

    @NotBlank(message = "codexModel不能为空")
    private String codexModel;
}
