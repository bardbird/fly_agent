package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通知任务结果DTO
 *
 * @param emailsSent 发送的邮件数量
 * @param smsSent 发送的短信数量
 * @param systemNotificationsSent 发送的系统通知数量
 * @param failedNotifications 失败的通知列表
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResult {

    /**
     * 发送的邮件数量
     */
    private int emailsSent;

    /**
     * 发送的短信数量
     */
    private int smsSent;

    /**
     * 发送的系统通知数量
     */
    private int systemNotificationsSent;

    /**
     * 失败的通知列表
     */
    private List<String> failedNotifications;
}
