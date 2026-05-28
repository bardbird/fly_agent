package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 健康检查任务结果DTO
 *
 * @param dbStatus 数据库状态
 * @param redisStatus Redis状态
 * @param diskStatus 磁盘状态
 * @param memoryStatus 内存状态
 * @param overallStatus 整体状态
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class HealthCheckResult {

    /**
     * 数据库状态
     */
    private String dbStatus;

    /**
     * Redis状态
     */
    private String redisStatus;

    /**
     * 磁盘状态
     */
    private String diskStatus;

    /**
     * 内存状态
     */
    private String memoryStatus;

    /**
     * 整体状态
     */
    private String overallStatus;
}
