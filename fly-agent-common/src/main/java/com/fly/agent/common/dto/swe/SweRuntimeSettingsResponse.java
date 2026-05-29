package com.fly.agent.common.dto.swe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Runtime SWE-Pro settings response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SweRuntimeSettingsResponse {

    private List<SweRuntimeSettingDTO> settings;
}
