package com.fly.agent.common.constants;

/**
 * Agent 相关常量定义
 */
public final class AgentConstants {

    private AgentConstants() {
        // 防止实例化
    }

    // ==================== Redis 缓存 Key 模板 ====================

    /**
     * Agent 配置缓存 Key 模板
     * 参数: agentId
     */
    public static final String CACHE_KEY_AGENT_CONFIG = "agent:config:%s";
}
