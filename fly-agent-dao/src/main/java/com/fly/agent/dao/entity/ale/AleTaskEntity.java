package com.fly.agent.dao.entity.ale;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("ale_task")
public class AleTaskEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long runId;
    private String taskId;
    private String title;
    private String domain;
    private String discipline;
    private String scenario;
    private String difficulty;
    private String status;
    private String stage2Status;
    private BigDecimal score;
    private BigDecimal stage2Score;
    private BigDecimal stage2DurationS;
    private String taskDir;
    private String evidencePath;
    private String stage2ResultDir;
    private String summary;
    private String errorMessage;
    private String stage2Error;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
