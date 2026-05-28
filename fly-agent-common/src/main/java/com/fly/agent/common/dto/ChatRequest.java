package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求DTO
 *
 * @param conversationId 会话ID
 * @param message 用户消息内容
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 用户消息内容
     */
    private String message;
}
