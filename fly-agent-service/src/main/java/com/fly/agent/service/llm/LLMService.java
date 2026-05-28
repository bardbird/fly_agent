package com.fly.agent.service.llm;

import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * LLM 服务
 * 提供统一的 LLM 调用接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final ZhipuAIClient zhipuAIClient;

    /**
     * LLM 响应 choices 路径常量
     */
    private static final String RESPONSE_CHOICES_PATH = "choices";
    private static final String RESPONSE_MESSAGE_PATH = "message";
    private static final String RESPONSE_CONTENT_PATH = "content";

    /**
     * 发送聊天消息（无额外配置）
     *
     * @param model   模型名称
     * @param messages 消息列表
     * @return LLM 响应内容
     */
    public Mono<String> chat(String model, List<Map<String, String>> messages) {
        return chat(model, messages, null);
    }

    /**
     * 发送聊天消息（带额外配置）
     *
     * @param model      模型名称
     * @param messages   消息列表
     * @param extraConfig 额外配置参数
     * @return LLM 响应内容
     */
    public Mono<String> chat(String model, List<Map<String, String>> messages,
                             Map<String, Object> extraConfig) {
        return zhipuAIClient.chatCompletion(model, messages, extraConfig)
                .map(this::extractContent)
                .doOnSuccess(content -> log.info("LLM response received, length: {}", content.length()));
    }

    /**
     * 从响应中提取内容
     *
     * @param response LLM 响应 JSON 对象
     * @return 响应内容
     */
    private String extractContent(JSONObject response) {
        return response.getJSONArray(RESPONSE_CHOICES_PATH)
                .getJSONObject(0)
                .getJSONObject(RESPONSE_MESSAGE_PATH)
                .getString(RESPONSE_CONTENT_PATH);
    }
}
