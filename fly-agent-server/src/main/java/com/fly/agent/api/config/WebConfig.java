package com.fly.agent.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类
 * 配置跨域请求等 Web 相关设置
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * API 路径映射
     */
    private static final String API_PATH_PATTERN = "/api/**";

    /**
     * 允许的 HTTP 方法
     */
    private static final String[] ALLOWED_METHODS = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};

    /**
     * 配置跨域请求
     *
     * @param registry CORS 注册表
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(API_PATH_PATTERN)
                .allowedOrigins("*")
                .allowedMethods(ALLOWED_METHODS)
                .allowedHeaders("*");
    }
}
