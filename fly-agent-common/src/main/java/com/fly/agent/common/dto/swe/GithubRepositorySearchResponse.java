package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub repository search response.
 */
@Data
public class GithubRepositorySearchResponse {

    private String language;

    private String githubLanguage;

    private Integer totalCount;

    private Boolean incompleteResults;

    private Integer page;

    private Integer perPage;

    private List<GithubRepositoryDTO> repositories = new ArrayList<>();
}
