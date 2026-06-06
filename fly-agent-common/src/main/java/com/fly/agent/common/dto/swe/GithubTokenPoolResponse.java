package com.fly.agent.common.dto.swe;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GitHub token pool state.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubTokenPoolResponse {

    private List<GithubTokenPoolItemDTO> tokens;

    private int totalCount;

    private int availableCount;

    private int inUseCount;

    private int unavailableTodayCount;
}
