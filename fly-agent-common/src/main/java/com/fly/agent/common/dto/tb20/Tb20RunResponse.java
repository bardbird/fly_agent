package com.fly.agent.common.dto.tb20;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Tb20RunResponse {

    private String runId;

    private String kind;

    private String status;

    private String skillName;

    private String workspace;

    private String outputRoot;

    private String logPath;

    private List<String> command = new ArrayList<>();

    private List<Tb20RunStageStatusDTO> stages = new ArrayList<>();

    private List<Tb20RunArtifactDTO> artifacts = new ArrayList<>();

    private String startedAt;

    private String finishedAt;

    private Integer exitCode;

    private String errorMessage;
}
