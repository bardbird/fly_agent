package com.fly.agent.dao.entity.ale;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ale_run")
public class AleRunEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String runKey;
    private String domain;
    private String discipline;
    private String scenario;
    private String difficulty;
    private String inputMode;
    private String outputMode;
    private String verificationMode;
    private String referenceStrategy;
    private Integer targetCount;
    private String codexModel;
    private String status;
    private Integer progressPercent;
    private Integer totalTasks;
    private Integer completedTasks;
    private Integer failedTasks;
    private Integer blockedTasks;
    private String outputRoot;
    private String logPath;
    private String summaryPath;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
