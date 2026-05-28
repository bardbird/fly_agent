package com.fly.agent.common.util;

/**
 * JSON 工具类
 * 提供 JSON 字符串转义和反斜杠处理功能
 */
public class JsonUtil {

    /**
     * 为 JSON 字符串值转义特殊字符
     * 将字符串安全地嵌入到 JSON 中，避免特殊字符导致格式错误
     *
     * @param content 原始内容
     * @return 转义后的内容，可直接用于 JSON 字符串值
     */
    public static String escapeForJson(String content) {
        if (content == null) {
            return "";
        }

        return content
                .replace("\\", "\\\\")   // 反斜杠必须最先替换
                .replace("\"", "\\\"")    // 双引号
                .replace("\n", "\\n")     // 换行符
                .replace("\r", "\\r")     // 回车符
                .replace("\t", "\\t");    // 制表符
    }

    private JsonUtil() {
        // 工具类，禁止实例化
    }
}
