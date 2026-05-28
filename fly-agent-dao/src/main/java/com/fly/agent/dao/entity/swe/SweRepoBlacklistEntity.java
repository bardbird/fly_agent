package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Repository denylist entry for SWE-Pro discovery.
 */
@Data
@TableName("swe_repo_blacklist")
public class SweRepoBlacklistEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String repo;

    private String githubUrl;

    private Integer githubStars;

    private String benchmarks;

    private String datasets;

    private String splits;

    private Integer instanceCount;

    private String languages;

    private String exampleInstanceId;

    private String exampleBaseCommit;

    private String sourceFile;

    private String sourceSheet;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
