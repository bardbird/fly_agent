package com.fly.agent.task.job;

import com.fly.agent.common.dto.HealthCheckResult;
import com.fly.agent.common.dto.NotificationResult;
import com.fly.agent.common.dto.StatisticsResult;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 业务场景任务示例
 * 演示常见的定时业务场景
 */
@Slf4j
@Component
public class BusinessDemoJob {

    // ==================== 格式化器 ====================

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // ==================== 数据清理任务常量 ====================

    private static final int DEFAULT_RETENTION_DAYS = 30;
    private static final int CLEANUP_TOTAL_TO_CHECK = 1000;
    private static final int CLEANUP_EXPIRE_RATE = 20;  // 20% 过期率
    private static final int CLEANUP_LOG_INTERVAL = 10;
    private static final int CLEANUP_DELAY_MS = 5;

    // ==================== 统计报表任务常量 ====================

    private static final int BASE_TOTAL_USERS = 10000;
    private static final int USER_VARIATION = 500;
    private static final double ACTIVE_USER_RATE = 0.6;
    private static final int MAX_NEW_USERS = 100;
    private static final int BASE_TOTAL_ORDERS = 500;
    private static final int ORDER_VARIATION = 200;
    private static final int BASE_ORDER_AMOUNT = 100;
    private static final int ORDER_AMOUNT_VARIATION = 50;

    // ==================== 数据同步任务常量 ====================

    private static final int SYNC_TOTAL_PAGES = 10;
    private static final int SYNC_PAGE_SIZE = 100;
    private static final int SYNC_MIN_PER_PAGE = 80;
    private static final int SYNC_DELAY_MS = 200;

    // ==================== 健康检查任务常量 ====================

    private static final int DB_FAILURE_RATE = 5;         // 5% 失败率
    private static final int REDIS_FAILURE_RATE = 5;      // 5% 失败率
    private static final int DISK_FAILURE_RATE = 10;      // 10% 失败率
    private static final int MEMORY_FAILURE_RATE = 15;    // 15% 失败率
    private static final int HEALTH_CHECK_RANDOM_BOUND = 100;

    // ==================== 通知任务常量 ====================

    private static final int EMAIL_MIN_COUNT = 10;
    private static final int EMAIL_MAX_COUNT = 50;
    private static final int SMS_MIN_COUNT = 5;
    private static final int SMS_MAX_COUNT = 20;
    private static final int SYSTEM_MIN_COUNT = 20;
    private static final int SYSTEM_MAX_COUNT = 100;
    private static final int NOTIFICATION_DELAY_MS = 500;

    // ==================== 任务方法 ====================

    /**
     * 数据清理任务示例
     * 定期清理过期数据
     */
    @XxlJob("demoDataCleanupJob")
    public void demoDataCleanupJob() {
        log.info("======= Data Cleanup Job Started =======");

        try {
            int retentionDays = parseRetentionDays(XxlJobHelper.getJobParam());
            LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);

            log.info("Cleaning up data before {} (retention days: {})", cutoffDate, retentionDays);

            int cleanedCount = performDataCleanup();

            String successMessage = String.format("Successfully cleaned up %d expired records", cleanedCount);
            log.info("Data cleanup completed, {}", successMessage);
            XxlJobHelper.handleSuccess(successMessage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Data cleanup job interrupted", e);
            XxlJobHelper.handleFail("Data cleanup interrupted");

        } catch (Exception e) {
            log.error("Data cleanup job failed", e);
            XxlJobHelper.handleFail("Data cleanup failed: " + e.getMessage());
        }
    }

    /**
     * 统计报表任务示例
     * 定期生成数据统计报表
     */
    @XxlJob("demoStatisticsJob")
    public void demoStatisticsJob() {
        log.info("======= Statistics Job Started =======");

        try {
            LocalDate statisticsDate = parseStatisticsDate(XxlJobHelper.getJobParam());
            log.info("Generating statistics report for {}", statisticsDate);

            StatisticsResult result = generateStatistics();

            log.info("Statistics result - Total users: {}, Active users: {}, New users: {}, " +
                            "Total orders: {}, Total amount: {:.2f}",
                    result.getTotalUsers(), result.getActiveUsers(), result.getNewUsers(),
                    result.getTotalOrders(), result.getTotalAmount());

            log.info("Report saved to database");
            XxlJobHelper.handleSuccess("Statistics report generated successfully for: " + statisticsDate);

        } catch (Exception e) {
            log.error("Statistics job failed", e);
            XxlJobHelper.handleFail("Statistics generation failed: " + e.getMessage());
        }
    }

    /**
     * 数据同步任务示例
     * 从外部系统同步数据
     */
    @XxlJob("demoDataSyncJob")
    public void demoDataSyncJob() {
        log.info("======= Data Sync Job Started =======");

        try {
            String param = XxlJobHelper.getJobParam();
            log.info("Sync parameter: {}", param);

            int totalSynced = performDataSync();

            String successMessage = String.format("Successfully synced %d records", totalSynced);
            log.info("Data sync completed, {}", successMessage);
            XxlJobHelper.handleSuccess(successMessage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Data sync job interrupted", e);
            XxlJobHelper.handleFail("Data sync interrupted");

        } catch (Exception e) {
            log.error("Data sync job failed", e);
            XxlJobHelper.handleFail("Data sync failed: " + e.getMessage());
        }
    }

    /**
     * 健康检查任务示例
     * 定期检查系统健康状态
     */
    @XxlJob("demoHealthCheckJob")
    public void demoHealthCheckJob() {
        log.info("======= Health Check Job Started =======");

        try {
            HealthCheckResult result = performHealthChecks();

            String report = buildHealthReport(result);
            log.info("\n{}", report);

            if ("HEALTHY".equals(result.getOverallStatus())) {
                XxlJobHelper.handleSuccess("System health check passed");
            } else {
                XxlJobHelper.handleFail("System health check detected anomalies");
            }

        } catch (Exception e) {
            log.error("Health check job failed", e);
            XxlJobHelper.handleFail("Health check failed: " + e.getMessage());
        }
    }

    /**
     * 定时通知任务示例
     * 定时发送通知或提醒
     */
    @XxlJob("demoNotificationJob")
    public void demoNotificationJob() {
        log.info("======= Notification Job Started =======");

        try {
            String param = XxlJobHelper.getJobParam();
            log.info("Notification parameter: {}", param);

            NotificationResult result = sendNotifications();

            log.info("Notifications sent - Email: {}, SMS: {}, System: {}, Total: {}",
                    result.getEmailsSent(), result.getSmsSent(), result.getSystemNotificationsSent(),
                    result.getEmailsSent() + result.getSmsSent() + result.getSystemNotificationsSent());

            String successMessage = String.format("Successfully sent %d notifications",
                    result.getEmailsSent() + result.getSmsSent() + result.getSystemNotificationsSent());
            XxlJobHelper.handleSuccess(successMessage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Notification job interrupted", e);
            XxlJobHelper.handleFail("Notification sending interrupted");

        } catch (Exception e) {
            log.error("Notification job failed", e);
            XxlJobHelper.handleFail("Notification sending failed: " + e.getMessage());
        }
    }

    // ==================== 数据清理相关方法 ====================

    /**
     * 解析保留天数参数
     */
    private int parseRetentionDays(String param) {
        if (param != null && !param.isEmpty()) {
            try {
                return Integer.parseInt(param);
            } catch (NumberFormatException e) {
                log.warn("Invalid retention days parameter, using default: {}", DEFAULT_RETENTION_DAYS);
            }
        }
        return DEFAULT_RETENTION_DAYS;
    }

    /**
     * 执行数据清理
     */
    private int performDataCleanup() throws InterruptedException {
        int cleanedCount = 0;

        for (int i = 0; i < CLEANUP_TOTAL_TO_CHECK; i++) {
            // 模拟随机过期数据
            if (ThreadLocalRandom.current().nextInt(HEALTH_CHECK_RANDOM_BOUND) < CLEANUP_EXPIRE_RATE) {
                cleanedCount++;
                if (cleanedCount % CLEANUP_LOG_INTERVAL == 0) {
                    log.info("Cleaned {} expired records", cleanedCount);
                }
            }
            TimeUnit.MILLISECONDS.sleep(CLEANUP_DELAY_MS);
        }

        return cleanedCount;
    }

    // ==================== 统计报表相关方法 ====================

    /**
     * 解析统计日期参数
     */
    private LocalDate parseStatisticsDate(String param) {
        LocalDate statisticsDate = LocalDate.now().minusDays(1);

        if (param != null && !param.isEmpty()) {
            try {
                statisticsDate = LocalDate.parse(param, DATE_FORMATTER);
            } catch (Exception e) {
                log.warn("Invalid date parameter, using yesterday: {}", statisticsDate);
            }
        }

        return statisticsDate;
    }

    /**
     * 生成统计数据
     */
    private StatisticsResult generateStatistics() {
        int totalUsers = BASE_TOTAL_USERS + ThreadLocalRandom.current().nextInt(USER_VARIATION);
        int activeUsers = (int) (totalUsers * ACTIVE_USER_RATE);
        int newUsers = ThreadLocalRandom.current().nextInt(MAX_NEW_USERS);
        int totalOrders = BASE_TOTAL_ORDERS + ThreadLocalRandom.current().nextInt(ORDER_VARIATION);
        double totalAmount = totalOrders * (BASE_ORDER_AMOUNT + ThreadLocalRandom.current().nextInt(ORDER_AMOUNT_VARIATION));

        return new StatisticsResult(totalUsers, activeUsers, newUsers, totalOrders, totalAmount);
    }

    // ==================== 数据同步相关方法 ====================

    /**
     * 执行数据同步
     */
    private int performDataSync() throws InterruptedException {
        int totalSynced = 0;

        for (int page = 1; page <= SYNC_TOTAL_PAGES; page++) {
            log.info("Syncing page {}/{}", page, SYNC_TOTAL_PAGES);

            int syncedInPage = ThreadLocalRandom.current().nextInt(SYNC_MIN_PER_PAGE, SYNC_PAGE_SIZE + 1);
            totalSynced += syncedInPage;

            log.info("Page {} synced, {} records in this page", page, syncedInPage);
            TimeUnit.MILLISECONDS.sleep(SYNC_DELAY_MS);
        }

        return totalSynced;
    }

    // ==================== 健康检查相关方法 ====================

    /**
     * 执行健康检查
     */
    private HealthCheckResult performHealthChecks() {
        HealthCheckResult result = new HealthCheckResult();
        result.setDbStatus(checkDatabase() ? "OK" : "ERROR");
        result.setRedisStatus(checkRedis() ? "OK" : "ERROR");
        result.setDiskStatus(checkDiskSpace() ? "OK" : "LOW");
        result.setMemoryStatus(checkMemory() ? "OK" : "HIGH");
        result.setOverallStatus((checkDatabase() && checkRedis() && checkDiskSpace() && checkMemory()) ? "HEALTHY" : "UNHEALTHY");
        return result;
    }

    /**
     * 构建健康检查报告
     */
    private String buildHealthReport(HealthCheckResult result) {
        StringBuilder report = new StringBuilder();
        report.append("System Health Check Report\n");
        report.append("=========================\n");
        report.append("Check time: ").append(LocalDateTime.now().format(DATETIME_FORMATTER)).append("\n\n");
        report.append("Database status: ").append(result.getDbStatus()).append("\n");
        report.append("Redis status: ").append(result.getRedisStatus()).append("\n");
        report.append("Disk space: ").append(result.getDiskStatus()).append("\n");
        report.append("Memory usage: ").append(result.getMemoryStatus()).append("\n");
        report.append("Overall status: ").append(result.getOverallStatus()).append("\n");
        return report.toString();
    }

    /**
     * 检查数据库连接
     */
    private boolean checkDatabase() {
        return ThreadLocalRandom.current().nextInt(HEALTH_CHECK_RANDOM_BOUND) >= DB_FAILURE_RATE;
    }

    /**
     * 检查 Redis 连接
     */
    private boolean checkRedis() {
        return ThreadLocalRandom.current().nextInt(HEALTH_CHECK_RANDOM_BOUND) >= REDIS_FAILURE_RATE;
    }

    /**
     * 检查磁盘空间
     */
    private boolean checkDiskSpace() {
        return ThreadLocalRandom.current().nextInt(HEALTH_CHECK_RANDOM_BOUND) >= DISK_FAILURE_RATE;
    }

    /**
     * 检查内存使用
     */
    private boolean checkMemory() {
        return ThreadLocalRandom.current().nextInt(HEALTH_CHECK_RANDOM_BOUND) >= MEMORY_FAILURE_RATE;
    }

    // ==================== 通知相关方法 ====================

    /**
     * 发送通知
     */
    private NotificationResult sendNotifications() throws InterruptedException {
        NotificationResult result = new NotificationResult();

        int emailsSent = ThreadLocalRandom.current().nextInt(EMAIL_MIN_COUNT, EMAIL_MAX_COUNT + 1);
        result.setEmailsSent(emailsSent);
        log.info("Sending email notifications: {}", emailsSent);

        int smsSent = ThreadLocalRandom.current().nextInt(SMS_MIN_COUNT, SMS_MAX_COUNT + 1);
        result.setSmsSent(smsSent);
        log.info("Sending SMS notifications: {}", smsSent);

        int systemNotificationsSent = ThreadLocalRandom.current().nextInt(SYSTEM_MIN_COUNT, SYSTEM_MAX_COUNT + 1);
        result.setSystemNotificationsSent(systemNotificationsSent);
        log.info("Sending system notifications: {}", systemNotificationsSent);

        // 无失败通知
        result.setFailedNotifications(new ArrayList<>());

        TimeUnit.MILLISECONDS.sleep(NOTIFICATION_DELAY_MS);

        return result;
    }
}
