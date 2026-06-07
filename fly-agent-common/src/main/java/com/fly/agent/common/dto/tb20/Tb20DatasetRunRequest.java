package com.fly.agent.common.dto.tb20;

import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class Tb20DatasetRunRequest {

    @NotBlank(message = "domain不能为空")
    private String domain;

    @NotBlank(message = "sourceChannel不能为空")
    private String sourceChannel;

    private String brief;

    private String outputRoot;

    private String workspaceRoot;

    private JSONObject channelConfig = new JSONObject();
}
