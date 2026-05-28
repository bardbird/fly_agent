package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SWE-Pro pipeline stage execution record.
 */
@Data
@TableName("swe_pipeline_stage")
public class SwePipelineStageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;

    private String stageCode;

    private String stageName;

    private String status;

    private Integer sortOrder;

    private String resultSummary;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
