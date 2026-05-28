package com.fly.agent.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.common.enums.ConversationRole;
import com.fly.agent.common.exception.BusinessException;
import com.fly.agent.dao.entity.ConversationEntity;
import com.fly.agent.dao.entity.MessageEntity;
import com.fly.agent.dao.mapper.ConversationMapper;
import com.fly.agent.dao.mapper.MessageMapper;
import com.fly.agent.service.llm.LLMService;
import com.fly.agent.service.properties.ZhipuProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 对话服务
 * 提供对话创建和消息处理功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final LLMService llmService;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ZhipuProperties zhipuProperties;

    /**
     * 对话未找到错误消息
     */
    private static final String ERROR_CONVERSATION_NOT_FOUND = "Conversation not found";

    /**
     * 创建对话
     *
     * @param agentId Agent ID
     * @param userId  用户ID
     * @return 创建的对话实体
     */
    public ConversationEntity createConversation(Long agentId, String userId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setSessionId(UUID.randomUUID().toString());
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);

        LocalDateTime now = LocalDateTime.now();
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);

        conversationMapper.insert(conversation);
        log.info("Conversation created: sessionId={}, agentId={}, userId={}",
                conversation.getSessionId(), agentId, userId);

        return conversation;
    }

    /**
     * 发送聊天消息
     *
     * @param sessionId   会话ID
     * @param userMessage 用户消息
     * @return LLM 响应
     */
    public Mono<String> chat(String sessionId, String userMessage) {
        // 保存用户消息
        saveMessage(sessionId, ConversationRole.USER.getCode(), userMessage);

        // 获取对话历史
        List<MessageEntity> history = getConversationHistory(sessionId);

        // 调用 LLM
        return llmService.chat(zhipuProperties.getModel(), convertToMessages(history))
                .map(response -> {
                    // 保存助手响应
                    saveMessage(sessionId, ConversationRole.ASSISTANT.getCode(), response);
                    return response;
                });
    }

    /**
     * 保存消息
     *
     * @param sessionId 会话ID
     * @param role      消息角色
     * @param content   消息内容
     */
    private void saveMessage(String sessionId, String role, String content) {
        ConversationEntity conversation = getConversationBySessionId(sessionId);

        MessageEntity message = new MessageEntity();
        message.setConversationId(conversation.getId());
        message.setRole(role);
        message.setContent(content);
        message.setTimestamp(LocalDateTime.now());

        messageMapper.insert(message);
        log.debug("Message saved: conversationId={}, role={}", conversation.getId(), role);
    }

    /**
     * 根据会话ID获取对话
     *
     * @param sessionId 会话ID
     * @return 对话实体
     * @throws BusinessException 当对话不存在时抛出
     */
    private ConversationEntity getConversationBySessionId(String sessionId) {
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConversationEntity::getSessionId, sessionId);
        ConversationEntity conversation = conversationMapper.selectOne(wrapper);

        if (conversation == null) {
            throw new BusinessException(ERROR_CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    /**
     * 获取对话历史
     *
     * @param sessionId 会话ID
     * @return 消息历史列表
     */
    private List<MessageEntity> getConversationHistory(String sessionId) {
        // TODO: 实现获取对话历史
        return List.of();
    }

    /**
     * 转换为 LLM 消息格式
     *
     * @param history 消息历史
     * @return LLM 消息列表
     */
    private List<Map<String, String>> convertToMessages(List<MessageEntity> history) {
        // TODO: 转换为 LLM 消息格式
        return List.of();
    }
}
