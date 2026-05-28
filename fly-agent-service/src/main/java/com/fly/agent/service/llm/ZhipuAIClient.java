package com.fly.agent.service.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 智谱AI API 客户端
 * 封装智谱AI GLM 模型的调用接口
 */
@Slf4j
@Component
public class ZhipuAIClient {

    /**
     * 智谱AI Chat Completions API 地址
     */
    private static final String CHAT_COMPLETION_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    /**
     * API 超时时间（秒）
     */
    private static final int TIMEOUT_SECONDS = 30;

    /**
     * 请求参数名称
     */
    private static final String PARAM_MODEL = "model";
    private static final String PARAM_MESSAGES = "messages";

    private final WebClient webClient;
    private final String apiKey;

    /**
     * 构造函数
     *
     * @param apiKey 智谱AI API Key
     */
    public ZhipuAIClient(@Value("${agent.zhipu.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = buildWebClient();
    }

    /**
     * 构建 WebClient
     *
     * @return 配置好的 WebClient 实例
     */
    private WebClient buildWebClient() {
        return WebClient.builder()
                .baseUrl(CHAT_COMPLETION_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    /**
     * 调用聊天完成接口
     *
     * @param model       模型名称
     * @param messages    消息列表
     * @param extraConfig 额外配置参数
     * @return 响应 JSON 对象
     */
    public Mono<JSONObject> chatCompletion(String model, List<Map<String, String>> messages,
                                           Map<String, Object> extraConfig) {
        JSONObject requestBody = buildRequestBody(model, messages, extraConfig);

        log.info("Calling ZhipuAI chat completion, model: {}", model);

        return webClient.post()
                .bodyValue(requestBody.toJSONString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .map(this::parseResponse)
                .doOnError(error -> log.error("ZhipuAI call failed, model: {}", model, error));
    }

    /**
     * 构建请求体
     *
     * @param model       模型名称
     * @param messages    消息列表
     * @param extraConfig 额外配置
     * @return 请求体 JSON 对象
     */
    private JSONObject buildRequestBody(String model, List<Map<String, String>> messages,
                                        Map<String, Object> extraConfig) {
        JSONObject requestBody = new JSONObject();
        requestBody.put(PARAM_MODEL, model);
        requestBody.put(PARAM_MESSAGES, messages);

        if (extraConfig != null) {
            extraConfig.forEach(requestBody::put);
        }

        return requestBody;
    }

    /**
     * 解析响应
     *
     * @param responseBody 响应体字符串
     * @return 响应 JSON 对象
     */
    private JSONObject parseResponse(String responseBody) {
        return JSON.parseObject(responseBody);
    }
}
