package com.fly.agent.service.config;

import com.fly.agent.service.properties.ZhipuProperties;
import io.agentscope.core.model.OpenAIChatModel;
import io.agentscope.core.model.GenerateOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 配置类
 * 使用 OpenAIChatModel 调用智谱AI GLM-5（智谱AI提供 OpenAI 兼容 API）
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentScopeConfig {

    private final ZhipuProperties zhipuProperties;

    /**
     * 创建 OpenAIChatModel Bean
     * 智谱AI提供 OpenAI 兼容 API 端点：https://open.bigmodel.cn/api/paas/v4/
     */
    @Bean
    public OpenAIChatModel openAIChatModel() {
        log.info("初始化 OpenAIChatModel, model: {}", zhipuProperties.getModel());

        // 创建生成选项
        GenerateOptions options = GenerateOptions.builder()
                .temperature(zhipuProperties.getTemperature())
                .maxTokens(zhipuProperties.getMaxTokens())
                .build();

        // 构建模型，使用智谱AI的 OpenAI 兼容端点
        OpenAIChatModel model = OpenAIChatModel.builder()
                .apiKey(zhipuProperties.getApiKey())
                .modelName(zhipuProperties.getModel())  // glm-5
                .baseUrl("https://open.bigmodel.cn/api/coding/paas/v4")  // 智谱AI OpenAI 兼容端点
                .generateOptions(options)
                .stream(Boolean.TRUE)
                .build();

        log.info("OpenAIChatModel 初始化成功（使用智谱AI GLM-5）");
        return model;
    }
}
