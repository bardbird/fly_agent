package com.fly.agent.common.dto.tb20;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result returned by the TB 2.0 production pipeline endpoints.
 */
@Data
public class Tb20PipelineResponse {

    private String mode;

    private String sourceRoot;

    private String outputRoot;

    private String manifestPath;

    private String deliveryIndexPath;

    private JSONObject summary = new JSONObject();

    private JSONArray tasks = new JSONArray();

    private List<Tb20DependencyStatusDTO> dependencies = new ArrayList<>();
}
