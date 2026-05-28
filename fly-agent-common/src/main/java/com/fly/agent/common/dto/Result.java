package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一响应结果封装
 *
 * @param <T> 数据类型
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    /**
     * 响应码
     */
    private String code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 时间戳
     */
    private Long timestamp;

    /**
     * 成功响应码常量
     */
    public static final String SUCCESS_CODE = "SUCCESS";

    /**
     * 默认错误响应码常量
     */
    public static final String ERROR_CODE = "ERROR";

    /**
     * 默认成功消息
     */
    public static final String DEFAULT_SUCCESS_MESSAGE = "操作成功";

    /**
     * 创建成功响应
     *
     * @param data 响应数据
     * @param <T>  数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok(T data) {
        return new Result<>(SUCCESS_CODE, DEFAULT_SUCCESS_MESSAGE, data, System.currentTimeMillis());
    }

    /**
     * 创建无数据的成功响应
     *
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /**
     * 创建错误响应
     *
     * @param code    错误码
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> Result<T> error(String code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    /**
     * 创建默认错误码的错误响应
     *
     * @param message 错误消息
     * @param <T>     数据类型
     * @return 错误响应
     */
    public static <T> Result<T> error(String message) {
        return error(ERROR_CODE, message);
    }

    /**
     * 判断响应是否成功
     *
     * @return true表示成功
     */
    public boolean isSuccess() {
        return SUCCESS_CODE.equals(this.code);
    }
}
