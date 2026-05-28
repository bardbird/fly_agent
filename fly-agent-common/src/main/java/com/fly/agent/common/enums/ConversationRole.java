package com.fly.agent.common.enums;

import lombok.Getter;

/**
 * 对话角色枚举
 */
@Getter
public enum ConversationRole {

    /**
     * 用户
     */
    USER("user", "用户"),

    /**
     * 助手
     */
    ASSISTANT("assistant", "助手"),

    /**
     * 系统
     */
    SYSTEM("system", "系统"),

    /**
     * 工具
     */
    TOOL("tool", "工具");

    private final String code;
    private final String description;

    ConversationRole(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /**
     * 根据角色代码获取枚举
     *
     * @param code 角色代码
     * @return 对应的枚举值，未找到返回 null
     */
    public static ConversationRole fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ConversationRole role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        return null;
    }

    /**
     * 判断是否为系统角色
     *
     * @return true表示是系统角色
     */
    public boolean isSystem() {
        return this == SYSTEM;
    }

    /**
     * 判断是否为工具角色
     *
     * @return true表示是工具角色
     */
    public boolean isTool() {
        return this == TOOL;
    }
}
