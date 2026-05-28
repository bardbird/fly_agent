package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SWE-Pro pipeline run.
 */
@Data
@TableName("swe_pipeline_run")
public class SwePipelineRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long candidateId;

    private String status;

    private String currentStage;

    private String workspacePath;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
