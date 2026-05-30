package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Latest repository SCA/license compatibility report.
 */
@Data
@TableName("swe_repo_sca_report")
public class SweRepoScaReportEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String repo;

    private String primaryLanguage;

    private Integer githubStars;

    private String searchKeyword;

    private Integer searchMinStars;

    private Integer searchMaxStars;

    private String toolName;

    private String licenseSpdxId;

    private String licenseName;

    private String compatibilityStatus;

    private String compatibilityReason;

    private Integer componentCount;

    private String reportJson;

    private String rawJson;

    private LocalDateTime checkedAt;

    private LocalDateTime candidateLastScannedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
