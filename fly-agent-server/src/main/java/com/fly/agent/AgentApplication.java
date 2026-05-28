package com.fly.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fly Agent Platform 启动类
 * 基于 AgentScope Java 的企业级 AI 智能体平台
 */
@SpringBootApplication(scanBasePackages = "com.fly.agent")
@MapperScan("com.fly.agent.dao.mapper")
public class AgentApplication {

    /**
     * 应用程序入口
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
