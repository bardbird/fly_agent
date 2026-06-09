package com.fly.agent.service.tb20;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Terminal-Bench 2.0 production configuration.
 */
@Data
@Component
@ConfigurationProperties(prefix = "tb20")
public class Tb20Properties {

    private String toolkitRoot = "tools/tb20-production";

    private String productionRoot = "/home/ubuntu/tb20-output";

    private String runtimeVenv = "/home/ubuntu/tb20-runtime/.venv";

    private String python = "python3";

    private String defaultSourceRoot = "";
}
