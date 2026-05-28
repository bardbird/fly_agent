package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SWE-Pro pipeline artifact.
 */
@Data
@TableName("swe_artifact")
public class SweArtifactEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String artifactType;

    private String artifactName;

    private String artifactPath;

    private Long fileSize;

    private String checksum;

    private LocalDateTime createdAt;
}
