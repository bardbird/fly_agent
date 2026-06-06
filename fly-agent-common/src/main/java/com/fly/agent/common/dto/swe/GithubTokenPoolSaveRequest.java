package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.List;

/**
 * Request for adding GitHub tokens to the Redis-backed pool.
 */
@Data
public class GithubTokenPoolSaveRequest {

    private List<String> tokens;
}
