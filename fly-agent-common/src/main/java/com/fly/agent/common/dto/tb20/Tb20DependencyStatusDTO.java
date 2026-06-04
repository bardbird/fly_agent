package com.fly.agent.common.dto.tb20;

import lombok.Data;

/**
 * External dependency status for TB 2.0 production.
 */
@Data
public class Tb20DependencyStatusDTO {

    private String name;

    private String role;

    private String configuredPath;

    private Boolean present;

    private String status;

    private String note;
}
