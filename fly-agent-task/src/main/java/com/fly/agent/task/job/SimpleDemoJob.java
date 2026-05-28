package com.fly.agent.task.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 简单的定时任务示例
 * 用于演示 XXL-Job 基础功能
 */
@Slf4j
@Component
public class SimpleDemoJob {

    /**
     * 日期时间格式化器
     */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 默认处理数量
     */
    private static final int DEFAULT_PROCESS_COUNT = 100;

    /**
     * 模拟处理耗时（毫秒）
     */
    private static final int PROCESS_DELAY_MS = 10;

    /**
     * 简单任务示例：打印当前时间
     * 任务配置：Cron 表达式，如 "0/5 * * * * ?" 表示每5秒执行一次
     */
    @XxlJob("demoSimpleJob")
    public void demoSimpleJob() {
        log.info("======= XXL-Job Simple Demo =======");
        log.info("Task execution time: {}", LocalDateTime.now().format(DATETIME_FORMATTER));
        log.info("Task parameter: {}", XxlJobHelper.getJobParam());
        log.info("====================================");
    }

    /**
     * 数据处理任务示例
     * 演示如何获取任务参数和返回处理结果
     */
    @XxlJob("demoDataProcessJob")
    public void demoDataProcessJob() {
        String param = XxlJobHelper.getJobParam();
        log.info("Starting data processing, parameter: {}", param);

        try {
            int count = parseProcessCount(param);
            processData(count);

            String successMessage = String.format("Successfully processed %d records", count);
            log.info("Data processing completed, {}", successMessage);
            XxlJobHelper.handleSuccess(successMessage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Data processing interrupted", e);
            XxlJobHelper.handleFail("Data processing interrupted");

        } catch (Exception e) {
            log.error("Data processing failed", e);
            XxlJobHelper.handleFail("Data processing failed: " + e.getMessage());
        }
    }

    /**
     * 解析处理数量
     *
     * @param param 参数字符串
     * @return 处理数量
     */
    private int parseProcessCount(String param) {
        if (param != null && !param.isEmpty()) {
            try {
                return Integer.parseInt(param);
            } catch (NumberFormatException e) {
                log.warn("Invalid parameter format, using default count: {}", DEFAULT_PROCESS_COUNT);
            }
        }
        return DEFAULT_PROCESS_COUNT;
    }

    /**
     * 模拟数据处理
     *
     * @param count 处理数量
     * @throws InterruptedException 当线程被中断时抛出
     */
    private void processData(int count) throws InterruptedException {
        for (int i = 1; i <= count; i++) {
            log.info("Processing record {}/{}", i, count);
            Thread.sleep(PROCESS_DELAY_MS);
        }
    }
}
