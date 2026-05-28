package com.fly.agent.task;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fly.agent")
@MapperScan("com.fly.agent.dao.mapper")
public class FlyAgentTaskApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlyAgentTaskApplication.class, args);
    }
}
