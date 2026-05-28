package com.fly.agent.api.controller;

import com.fly.agent.common.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 欢迎页控制器
 * 提供根路径的默认响应
 */
@RestController
public class WelcomeController {

    @GetMapping("/")
    public Result<Map<String, String>> welcome() {
        return Result.ok(Map.of(
                "service", "Fly Agent Platform",
                "version", "1.0.0",
                "docs", "/api/v1/agents"
        ));
    }
}
