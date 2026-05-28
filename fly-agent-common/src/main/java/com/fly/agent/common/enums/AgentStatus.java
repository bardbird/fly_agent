package com.fly.agent.common.enums;

import lombok.Getter;

/**
 * Agent 状态枚举
 */
@Getter
public enum AgentStatus {

    /**
     * 已创建
     */
    CREATED("CREATED", "已创建"),

    /**
     * 启动中
     */
    STARTING("STARTING", "启动中"),

    /**
     * 运行中
     */
    RUNNING("RUNNING", "运行中"),

    /**
     * 停止中
     */
    STOPPING("STOPPING", "停止中"),

    /**
     * 已停止
     */
    STOPPED("STOPPED", "已停止"),

    /**
     * 错误
     */
    ERROR("ERROR", "错误");

    private final String code;
    private final String description;

    AgentStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据状态码获取枚举
     *
     * @param code 状态码
     * @return 对应的枚举值，未找到返回 null
     */
    public static AgentStatus fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (AgentStatus status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    /**
     * 判断是否为终止状态（已停止或错误）
     *
     * @return true表示是终止状态
     */
    public boolean isTerminal() {
        return this == STOPPED || this == ERROR;
    }

    /**
     * 判断是否为运行状态（运行中）
     *
     * @return true表示是运行状态
     */
    public boolean isRunning() {
        return this == RUNNING;
    }
}
