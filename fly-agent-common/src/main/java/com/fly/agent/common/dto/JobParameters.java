package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * XXL-Job 任务参数DTO
 * 用于接收任务执行时的参数配置
 *
 * @param batchSize 批次大小
 * @param timeout 超时时间（秒）
 * @param retryTimes 重试次数
 * @param targetDate 目标日期
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobParameters {

    /**
     * 批次大小
     */
    private int batchSize;

    /**
     * 超时时间（秒）
     */
    private int timeout;

    /**
     * 重试次数
     */
    private int retryTimes;

    /**
     * 目标日期
     */
    private String targetDate;
}
