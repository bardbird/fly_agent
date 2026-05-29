package com.fly.agent.common.dto.swe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Runtime SWE-Pro setting metadata returned to the UI.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SweRuntimeSettingDTO {

    private String key;

    private String label;

    private String value;

    private String maskedValue;

    private Boolean configured;

    private Boolean secret;

    private String description;
}
