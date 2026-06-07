package com.fly.agent.common.dto.tb20;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class Tb20RunQueryRequest {

    @NotBlank(message = "runId不能为空")
    private String runId;
}
