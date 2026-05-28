package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Merged GitHub PR candidate scored for SWE-Pro package production.
 *
 * <p>The issue and oracle fields intentionally mirror the SWE-bench task
 * boundary: a candidate must retain the resolved issue context, patch evidence,
 * and explicit FAIL_TO_PASS/PASS_TO_PASS lists or empty JSON arrays when those
 * lists are not available before package generation.</p>
 */
@Data
public class GithubPullCandidateDTO {

    private Long id;

    private String candidateId;

    private String repo;

    private Integer number;

    private String title;

    private String prUrl;

    private String issueUrl;

    private String issueNumbers;

    private String problemStatement;

    private String hintsText;

    private String baseCommit;

    private String fixCommit;

    private String mergeCommit;

    private String mergedAt;

    private String updatedAt;

    private String primaryLanguage;

    private String secondaryLanguages;

    private Integer patchFiles;

    private Integer sourceFiles;

    private Integer insertions;

    private Integer deletions;

    private Integer totalChanged;

    private Integer goldPatchFiles;

    private Integer goldSourceFiles;

    private Integer goldInsertions;

    private Integer goldDeletions;

    private Integer goldTotalChanged;

    private Integer testPatchFiles;

    private Integer testInsertions;

    private Integer testDeletions;

    private Integer testTotalChanged;

    private Boolean testPatchPresent;

    private String failToPass;

    private String passToPass;

    private String benchmarkStatus;

    private String failedHistoryStatus;

    private Double generatedOrI18nRatio;

    private Integer score;

    private String candidateGrade;

    private String gradeReason;

    private String candidateStatus;

    private String duplicateStatus;

    private List<String> strengths = new ArrayList<>();

    private List<String> risks = new ArrayList<>();

    private String precheckPlan;

    private List<String> sampleFiles = new ArrayList<>();
}
