package com.fly.agent.common.dto.tb20;

import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

@Data
public class Tb20ConfigResponse {

    private String scope;

    private JSONObject values = new JSONObject();
}
