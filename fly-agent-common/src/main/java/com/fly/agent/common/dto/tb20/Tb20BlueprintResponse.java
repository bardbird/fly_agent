package com.fly.agent.common.dto.tb20;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Static production blueprint for Terminal-Bench 2.0 task delivery.
 */
@Data
public class Tb20BlueprintResponse {

    private String standard = "Terminal-Bench 2.0";

    private List<String> requiredTaskFiles = new ArrayList<>();

    private List<String> optionalDeliveryLogs = new ArrayList<>();

    private List<Tb20StageDTO> stages = new ArrayList<>();

    private List<String> nonAutomatableBoundaries = new ArrayList<>();

    private List<String> aiScaleOutControls = new ArrayList<>();

    private List<Tb20DependencyStatusDTO> dependencies = new ArrayList<>();
}
