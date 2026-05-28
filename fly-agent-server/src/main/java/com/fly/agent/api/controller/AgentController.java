package com.fly.agent.api.controller;

import com.fly.agent.common.dto.Result;
import com.fly.agent.common.dto.IdRequest;
import com.fly.agent.dao.entity.AgentEntity;
import com.fly.agent.service.agent.AgentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent 管理控制器
 * 提供 Agent 的 CRUD 接口和生命周期管理接口
 */
@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
@Validated
public class AgentController {

    private final AgentService agentService;

    /**
     * 创建 Agent
     *
     * @param agent Agent 实体
     * @return 创建的 Agent
     */
    @PostMapping
    public Result<AgentEntity> createAgent(@RequestBody AgentEntity agent) {
        return Result.ok(agentService.createAgent(agent));
    }

    /**
     * 根据 ID 查询 Agent
     *
     * @param id Agent ID
     * @return Agent 实体
     */
    @GetMapping("/detail")
    public Result<AgentEntity> getAgent(@NotNull(message = "id不能为空") @RequestParam("id") Long id) {
        return Result.ok(agentService.getAgent(id));
    }

    /**
     * 查询所有 Agent
     *
     * @return Agent 列表
     */
    @GetMapping
    public Result<List<AgentEntity>> listAgents() {
        return Result.ok(agentService.listAgents());
    }

    /**
     * 启动 Agent
     *
     * @param id Agent ID
     * @return 操作结果
     */
    @PostMapping("/start")
    public Result<Void> startAgent(@Valid @RequestBody IdRequest request) {
        agentService.startAgent(request.getId());
        return Result.ok();
    }

    /**
     * 停止 Agent
     *
     * @param id Agent ID
     * @return 操作结果
     */
    @PostMapping("/stop")
    public Result<Void> stopAgent(@Valid @RequestBody IdRequest request) {
        agentService.stopAgent(request.getId());
        return Result.ok();
    }
}
