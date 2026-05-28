package com.fly.agent.common.exception;

import lombok.Getter;

/**
 * 业务异常
 * 用于封装业务逻辑中的异常情况
 */
@Getter
public class BusinessException extends RuntimeException {

    /**
     * 默认业务错误码
     */
    public static final String DEFAULT_CODE = "BUSINESS_ERROR";

    /**
     * 错误码
     */
    private final String code;

    /**
     * 创建业务异常
     *
     * @param code    错误码
     * @param message 错误消息
     */
    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 创建默认错误码的业务异常
     *
     * @param message 错误消息
     */
    public BusinessException(String message) {
        this(DEFAULT_CODE, message);
    }

    /**
     * 创建业务异常（带原因）
     *
     * @param code    错误码
     * @param message 错误消息
     * @param cause   原始异常
     */
    public BusinessException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 创建默认错误码的业务异常（带原因）
     *
     * @param message 错误消息
     * @param cause   原始异常
     */
    public BusinessException(String message, Throwable cause) {
        this(DEFAULT_CODE, message, cause);
    }
}
