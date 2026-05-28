package com.fly.agent.api.controller;

import com.fly.agent.common.dto.ChatRequest;
import com.fly.agent.service.agentscope.AgentScopeChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 聊天控制器
 * 提供基于 AgentScope ReActAgent 的对话接口
 * 通过 OpenAIChatModel 调用智谱AI GLM-5（OpenAI 兼容模式）
 * 支持 SSE 流式响应
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final AgentScopeChatService chatService;

    /**
     * 创建新对话
     * 返回唯一的会话 ID
     *
     * @return 会话 ID
     */
    @PostMapping("/conversations")
    public Mono<String> createConversation() {
        String sessionId = java.util.UUID.randomUUID().toString();
        log.info("创建新对话, sessionId: {}", sessionId);
        return Mono.just(sessionId);
    }

    /**
     * 发送消息（非流式）
     *
     * @param request 请求体 {"conversationId": "xxx", "message": "用户消息"}
     * @return AI 完整响应
     */
    @PostMapping("/completions")
    public Mono<String> chat(@RequestBody ChatRequest request) {
        log.info("接收到聊天请求, conversationId: {}", request.getConversationId());

        return chatService.chat(request.getMessage())
                .doOnSuccess(response -> log.info("聊天响应成功, 长度: {}", response.length()));
    }

    /**
     * 发送消息（流式响应 - SSE）
     *
     * @param request 请求体 {"conversationId": "xxx", "message": "用户消息"}
     * @return SSE 流式响应
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(@RequestBody ChatRequest request) {
        log.info("接收到流式聊天请求, conversationId: {}", request.getConversationId());

        return chatService.chatStream(request.getMessage())
                .map(content -> ServerSentEvent.<String>builder()
                        .data(content)
                        .build())
                .doOnComplete(() -> log.info("流式响应完成"))
                .doOnError(error -> log.error("流式响应错误", error));
    }
}
