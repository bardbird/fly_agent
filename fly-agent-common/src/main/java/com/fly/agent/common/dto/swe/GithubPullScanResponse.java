package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of scanning merged PRs from a GitHub repository.
 *
 * <p>`skippedNoResolvedIssue` counts merged PRs that do not contain GitHub
 * closing keywords, which keeps the collection boundary aligned with
 * issue-grounded SWE-bench-style tasks.</p>
 */
@Data
public class GithubPullScanResponse {

    private String repo;

    private Integer days;

    private Integer limit;

    private Integer page;

    private Integer perPage;

    private Integer nextPage;

    private Boolean hasMore;

    private Integer scannedPulls;

    private Integer mergedPulls;

    private Integer skippedUnmerged;

    private Integer skippedOutOfRange;

    private Integer skippedByFilter;

    private Integer skippedNoResolvedIssue;

    private Integer skippedDelivered;

    private Integer minGoldSourceFiles;

    private Integer maxGoldSourceFiles;

    private Integer minGoldLines;

    private Integer maxGoldLines;

    private List<GithubPullCandidateDTO> candidates = new ArrayList<>();
}
