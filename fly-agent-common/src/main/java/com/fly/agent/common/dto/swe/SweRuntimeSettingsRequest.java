package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.Map;

/**
 * Request for updating runtime SWE-Pro settings.
 */
@Data
public class SweRuntimeSettingsRequest {

    private Map<String, String> values;
}
