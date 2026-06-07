package com.fly.agent.common.dto.tb20;

import com.alibaba.fastjson2.JSONObject;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class Tb20ConfigRequest {

    @NotBlank(message = "scope不能为空")
    private String scope;

    private JSONObject values = new JSONObject();
}
