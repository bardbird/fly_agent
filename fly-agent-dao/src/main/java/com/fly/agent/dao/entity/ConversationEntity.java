package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话实体
 */
@Data
@TableName("conversation")
public class ConversationEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话ID（UUID）
     */
    private String sessionId;

    /**
     * 关联的 Agent ID
     */
    private Long agentId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 对话标题
     */
    private String title;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
