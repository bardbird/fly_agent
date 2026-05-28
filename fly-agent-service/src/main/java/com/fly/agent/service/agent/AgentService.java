package com.fly.agent.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.common.constants.AgentConstants;
import com.fly.agent.common.enums.AgentStatus;
import com.fly.agent.common.exception.BusinessException;
import com.fly.agent.dao.entity.AgentEntity;
import com.fly.agent.dao.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent 服务
 * 提供 Agent 的 CRUD 操作和生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentRegistry agentRegistry;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Agent 未找到错误消息
     */
    private static final String ERROR_AGENT_NOT_FOUND = "Agent not found";

    /**
     * 创建 Agent
     *
     * @param agent Agent 实体
     * @return 创建后的 Agent
     */
    public AgentEntity createAgent(AgentEntity agent) {
        agent.setStatus(AgentStatus.CREATED.getCode());
        LocalDateTime now = LocalDateTime.now();
        agent.setCreatedAt(now);
        agent.setUpdatedAt(now);
        agentMapper.insert(agent);
        log.info("Agent created: id={}, name={}", agent.getId(), agent.getAgentName());
        return agent;
    }

    /**
     * 根据 ID 查询 Agent
     *
     * @param id Agent ID
     * @return Agent 实体
     */
    public AgentEntity getAgent(Long id) {
        return agentMapper.selectById(id);
    }

    /**
     * 根据名称查询 Agent
     *
     * @param agentName Agent 名称
     * @return Agent 实体
     */
    public AgentEntity getAgentByName(String agentName) {
        LambdaQueryWrapper<AgentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentEntity::getAgentName, agentName);
        return agentMapper.selectOne(wrapper);
    }

    /**
     * 查询所有 Agent
     *
     * @return Agent 列表
     */
    public List<AgentEntity> listAgents() {
        return agentMapper.selectList(null);
    }

    /**
     * 启动 Agent
     *
     * @param id Agent ID
     * @throws BusinessException 当 Agent 不存在时抛出
     */
    public void startAgent(Long id) {
        AgentEntity agent = validateAgentExists(id);

        agent.setStatus(AgentStatus.RUNNING.getCode());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);

        // 注册到 AgentRegistry
        agentRegistry.registerAgent(agent);

        // 缓存配置
        String cacheKey = String.format(AgentConstants.CACHE_KEY_AGENT_CONFIG, id);
        redisTemplate.opsForValue().set(cacheKey, agent);

        log.info("Agent started: id={}, name={}", id, agent.getAgentName());
    }

    /**
     * 停止 Agent
     *
     * @param id Agent ID
     * @throws BusinessException 当 Agent 不存在时抛出
     */
    public void stopAgent(Long id) {
        AgentEntity agent = validateAgentExists(id);

        agent.setStatus(AgentStatus.STOPPED.getCode());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);

        // 从 Registry 移除
        agentRegistry.unregisterAgent(id);

        log.info("Agent stopped: id={}, name={}", id, agent.getAgentName());
    }

    /**
     * 验证 Agent 是否存在
     *
     * @param id Agent ID
     * @return Agent 实体
     * @throws BusinessException 当 Agent 不存在时抛出
     */
    private AgentEntity validateAgentExists(Long id) {
        AgentEntity agent = getAgent(id);
        if (agent == null) {
            throw new BusinessException(ERROR_AGENT_NOT_FOUND);
        }
        return agent;
    }
}
