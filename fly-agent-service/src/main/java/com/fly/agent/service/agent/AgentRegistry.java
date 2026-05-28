package com.fly.agent.service.agent;

import com.fly.agent.dao.entity.AgentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册表
 * 管理运行中的 Agent 实例
 */
@Slf4j
@Component
public class AgentRegistry {

    /**
     * Agent 注册表
     * Key: Agent ID, Value: Agent 实体
     */
    private final ConcurrentHashMap<Long, AgentEntity> registry = new ConcurrentHashMap<>();

    /**
     * 注册 Agent
     *
     * @param agent Agent 实体
     */
    public void registerAgent(AgentEntity agent) {
        registry.put(agent.getId(), agent);
        log.info("Agent registered: id={}, name={}", agent.getId(), agent.getAgentName());
    }

    /**
     * 注销 Agent
     *
     * @param agentId Agent ID
     */
    public void unregisterAgent(Long agentId) {
        registry.remove(agentId);
        log.info("Agent unregistered: id={}", agentId);
    }

    /**
     * 获取 Agent
     *
     * @param agentId Agent ID
     * @return Agent 实体，不存在返回 null
     */
    public AgentEntity getAgent(Long agentId) {
        return registry.get(agentId);
    }

    /**
     * 判断 Agent 是否已注册
     *
     * @param agentId Agent ID
     * @return true 表示已注册
     */
    public boolean containsAgent(Long agentId) {
        return registry.containsKey(agentId);
    }

    /**
     * 获取所有已注册的 Agent 数量
     *
     * @return Agent 数量
     */
    public int size() {
        return registry.size();
    }
}
