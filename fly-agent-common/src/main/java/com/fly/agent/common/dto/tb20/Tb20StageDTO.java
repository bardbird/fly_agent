package com.fly.agent.common.dto.tb20;

import lombok.Data;

/**
 * A production stage in the TB 2.0 task pipeline.
 */
@Data
public class Tb20StageDTO {

    private String code;

    private String name;

    private String automationLevel;

    private String triggerMode;

    private String owner;

    private String input;

    private String output;

    private String gate;
}
