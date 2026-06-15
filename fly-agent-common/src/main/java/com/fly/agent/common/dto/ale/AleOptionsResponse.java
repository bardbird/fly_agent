package com.fly.agent.common.dto.ale;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AleOptionsResponse {

    private List<AleOptionDTO> domains = new ArrayList<>();
    private List<AleOptionDTO> disciplines = new ArrayList<>();
    private List<AleOptionDTO> scenarios = new ArrayList<>();
    private List<AleOptionDTO> difficulties = new ArrayList<>();
    private List<AleOptionDTO> inputModes = new ArrayList<>();
    private List<AleOptionDTO> outputModes = new ArrayList<>();
    private List<AleOptionDTO> verificationModes = new ArrayList<>();
    private List<AleOptionDTO> referenceStrategies = new ArrayList<>();
    private List<AleOptionDTO> codexModels = new ArrayList<>();
}
