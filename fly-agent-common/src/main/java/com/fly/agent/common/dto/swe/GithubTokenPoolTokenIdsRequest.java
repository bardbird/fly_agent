package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.List;

/**
 * Request carrying GitHub token ids.
 */
@Data
public class GithubTokenPoolTokenIdsRequest {

    private List<String> ids;
}
