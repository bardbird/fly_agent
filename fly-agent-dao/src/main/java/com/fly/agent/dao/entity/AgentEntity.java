package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 配置实体
 */
@Data
@TableName("agent_config")
public class AgentEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Agent 名称
     */
    private String agentName;

    /**
     * Agent 类型
     */
    private String agentType;

    /**
     * 系统提示词
     */
    private String systemPrompt;

    /**
     * LLM 配置（JSON格式）
     */
    private String llmConfig;

    /**
     * 关联的工具列表（JSON格式）
     */
    private String tools;

    /**
     * Agent 状态
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
