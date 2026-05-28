package com.fly.agent.service.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 智谱 AI 配置属性
 * 从 application.yml 读取 agent.zhipu 配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.zhipu")
public class ZhipuProperties {

    /**
     * API Key
     */
    private String apiKey;

    /**
     * 模型名称
     */
    private String model;

    /**
     * 温度参数
     */
    private Double temperature;

    /**
     * 最大 token 数
     */
    private Integer maxTokens;
}
