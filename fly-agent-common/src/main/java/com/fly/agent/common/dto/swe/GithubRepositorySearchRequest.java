package com.fly.agent.common.dto.swe;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * GitHub repository search request for SWE-Pro target discovery.
 */
@Data
public class GithubRepositorySearchRequest {

    @NotBlank(message = "language不能为空")
    private String language;

    private String keyword;

    private Integer minStars = 100;

    private Integer maxStars;

    private Integer page = 1;

    private Integer perPage = 20;

    private String sort = "stars";

    private String order = "desc";

    private Boolean precheckFilter;
}
