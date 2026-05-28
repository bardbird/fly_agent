package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Paginated persisted SWE-Pro PR candidates.
 */
@Data
public class GithubPullCandidateListResponse {

    private Integer page;

    private Integer perPage;

    private Long total;

    private Integer totalPages;

    private List<GithubPullCandidateDTO> candidates = new ArrayList<>();
}
