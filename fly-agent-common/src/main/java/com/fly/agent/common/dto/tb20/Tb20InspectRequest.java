package com.fly.agent.common.dto.tb20;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request for inspecting or packaging real Terminal-Bench 2.0 task data.
 */
@Data
public class Tb20InspectRequest {

    @NotBlank(message = "sourceRoot不能为空")
    private String sourceRoot;

    private String outputRoot;

    private List<String> taskPaths = new ArrayList<>();

    private Boolean copyTasks = false;

    private String harborRoot;

    private String terminalBenchRoot;
}
