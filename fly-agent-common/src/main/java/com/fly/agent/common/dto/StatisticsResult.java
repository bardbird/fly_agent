package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统计报表任务结果DTO
 *
 * @param totalUsers 总用户数
 * @param activeUsers 活跃用户数
 * @param newUsers 新增用户数
 * @param totalOrders 总订单数
 * @param totalAmount 总金额
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsResult {

    /**
     * 总用户数
     */
    private int totalUsers;

    /**
     * 活跃用户数
     */
    private int activeUsers;

    /**
     * 新增用户数
     */
    private int newUsers;

    /**
     * 总订单数
     */
    private int totalOrders;

    /**
     * 总金额
     */
    private double totalAmount;
}
