package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SWE-Pro production task.
 */
@Data
@TableName("swe_task")
public class SweTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long candidateId;

    private String taskName;

    private String repo;

    private String sourceUrl;

    private String baseCommit;

    private String fixCommit;

    private String repoLanguage;

    private String issueSpecificity;

    private String issueCategories;

    private String samplePath;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
