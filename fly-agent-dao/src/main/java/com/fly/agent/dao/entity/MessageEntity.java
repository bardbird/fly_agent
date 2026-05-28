package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体
 */
@Data
@TableName("message")
public class MessageEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的对话ID
     */
    private Long conversationId;

    /**
     * 消息角色（user/assistant/system/tool）
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 工具调用信息（JSON格式）
     */
    private String toolCalls;

    /**
     * Token 消耗量
     */
    private Integer tokens;

    /**
     * 消息时间戳
     */
    private LocalDateTime timestamp;
}
