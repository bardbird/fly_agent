package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Persisted GitHub merged PR candidate for SWE-Pro production.
 */
@Data
@TableName("swe_candidate")
public class SweCandidateEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String candidateId;

    private String repo;

    private Integer prNumber;

    private String prUrl;

    private String issueUrl;

    private String issueNumbers;

    private String problemStatement;

    private String hintsText;

    private String title;

    private String baseCommit;

    private String fixCommit;

    private String mergeCommit;

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

    private String strengths;

    private String risks;

    private String sampleFiles;

    private String rawJson;

    private LocalDateTime mergedAt;

    private LocalDateTime updatedAt;

    private LocalDateTime createdAt;

    private LocalDateTime modifiedAt;
}
