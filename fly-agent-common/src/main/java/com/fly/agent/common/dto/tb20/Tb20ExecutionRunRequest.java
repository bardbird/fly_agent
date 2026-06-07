package com.fly.agent.common.dto.tb20;

import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Tb20ExecutionRunRequest {

    @NotBlank(message = "sourceRoot不能为空")
    private String sourceRoot;

    private String outputRoot;

    private String workspaceRoot;

    private String agent = "claude-code";

    private String model;

    private Integer concurrency = 1;

    private Boolean failFast = false;

    private List<String> taskPaths = new ArrayList<>();

    private JSONObject executionConfig = new JSONObject();
}
