package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub repository candidate for SWE-Pro task sourcing.
 */
@Data
public class GithubRepositoryDTO {

    private Long githubId;

    private String name;

    private String fullName;

    private String htmlUrl;

    private String description;

    private String language;

    private Integer stargazersCount;

    private Integer forksCount;

    private Integer openIssuesCount;

    private String defaultBranch;

    private String pushedAt;

    private String licenseSpdxId;

    private String licenseName;

    private Boolean archived;

    private Boolean disabled;

    private List<String> topics = new ArrayList<>();

    private Integer productionScore;

    private String candidateGrade;

    private String gradeReason;

    private List<String> strengths = new ArrayList<>();

    private List<String> risks = new ArrayList<>();

    private String precheckPlan;
}
