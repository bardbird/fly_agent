package com.fly.agent.task.job;

import com.fly.agent.common.dto.DataRange;
import com.fly.agent.common.dto.JobParameters;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 带参数的任务示例
 * 演示如何使用任务参数进行业务处理
 */
@Slf4j
@Component
public class ParameterDemoJob {

    /**
     * 默认批次大小
     */
    private static final int DEFAULT_BATCH_SIZE = 10;

    /**
     * 默认超时时间（秒）
     */
    private static final int DEFAULT_TIMEOUT = 60;

    /**
     * 默认重试次数
     */
    private static final int DEFAULT_RETRY_TIMES = 1;

    /**
     * 默认目标日期
     */
    private static final String DEFAULT_TARGET_DATE = "today";

    /**
     * 进度报告间隔
     */
    private static final int PROGRESS_REPORT_INTERVAL = 10;

    /**
     * 批处理延迟（毫秒）
     */
    private static final int BATCH_DELAY_MS = 100;

    /**
     * 分片任务数据总数
     */
    private static final int SHARDING_TOTAL_DATA = 1000;

    /**
     * 分片处理延迟（毫秒）
     */
    private static final int SHARDING_DELAY_MS = 10;

    /**
     * 分片进度报告间隔
     */
    private static final int SHARDING_PROGRESS_INTERVAL = 100;

    /**
     * 参数化任务示例
     * 任务参数示例（JSON格式）:
     * {
     *   "batchSize": 50,
     *   "timeout": 300,
     *   "retryTimes": 3,
     *   "targetDate": "2025-02-05"
     * }
     */
    @XxlJob("demoParameterJob")
    public void demoParameterJob() {
        String paramJson = XxlJobHelper.getJobParam();
        log.info(">>>>>>> Parameter Job Started >>>>>>>>");
        log.info("Task parameter: {}", paramJson);

        try {
            JobParameters params = parseJobParameters(paramJson);
            log.info("Parsed parameters - batchSize: {}, timeout: {}, retryTimes: {}, targetDate: {}",
                    params.getBatchSize(), params.getTimeout(), params.getRetryTimes(), params.getTargetDate());

            executeBatchProcessing(params);

            String successMessage = String.format("Successfully processed %d batches", params.getBatchSize());
            log.info("Task completed successfully");
            XxlJobHelper.handleSuccess(successMessage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Task interrupted", e);
            XxlJobHelper.handleFail("Task interrupted");

        } catch (Exception e) {
            log.error("Task execution failed", e);
            XxlJobHelper.handleFail("Task execution failed: " + e.getMessage());
        }

        log.info("<<<<<<<< Parameter Job Ended <<<<<<<<");
    }

    /**
     * 解析任务参数
     *
     * @param paramJson JSON 参数字符串
     * @return 任务参数对象
     */
    private JobParameters parseJobParameters(String paramJson) {
        JobParameters params = new JobParameters();
        params.setBatchSize(getIntParam(paramJson, "batchSize", DEFAULT_BATCH_SIZE));
        params.setTimeout(getIntParam(paramJson, "timeout", DEFAULT_TIMEOUT));
        params.setRetryTimes(getIntParam(paramJson, "retryTimes", DEFAULT_RETRY_TIMES));
        params.setTargetDate(getStringParam(paramJson, "targetDate", DEFAULT_TARGET_DATE));
        return params;
    }

    /**
     * 执行批处理
     *
     * @param params 任务参数
     * @throws InterruptedException 当线程被中断时抛出
     */
    private void executeBatchProcessing(JobParameters params) throws InterruptedException {
        log.info("Starting batch processing...");
        for (int i = 1; i <= params.getBatchSize(); i++) {
            log.info("Processing batch {}/{}", i, params.getBatchSize());
            TimeUnit.MILLISECONDS.sleep(BATCH_DELAY_MS);

            if (i % PROGRESS_REPORT_INTERVAL == 0) {
                log.info("Task progress: {}/{}", i, params.getBatchSize());
            }
        }
    }

    /**
     * 分片任务示例
     * 用于演示分片广播场景
     * 注意：分片参数获取方式因 XXL-Job 版本而异
     * 2.4.0 版本请参考官方文档使用正确的 API
     */
    @XxlJob("demoShardingJob")
    public void demoShardingJob() {
        log.info("======= Sharding Job Demo =======");
        log.info("Task parameter: {}", XxlJobHelper.getJobParam());

        try {
            // 模拟分片处理逻辑
            // 实际使用时需要根据 XXL-Job 版本调用正确的分片 API
            int shardIndex = 0;  // XxlJobHelper.getShardingIndex();
            int shardTotal = 1;  // XxlJobHelper.getShardingTotal();

            log.info("Sharding index: {}/{}", shardIndex, shardTotal);

            executeSharding(shardIndex, shardTotal);

            String successMessage = String.format("Shard %d successfully processed %d records",
                    shardIndex, getShardDataCount(shardIndex, shardTotal));
            XxlJobHelper.handleSuccess(successMessage);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Sharding task interrupted", e);
            XxlJobHelper.handleFail("Sharding task interrupted");

        } catch (Exception e) {
            log.error("Sharding task execution failed", e);
            XxlJobHelper.handleFail("Sharding task failed: " + e.getMessage());
        }
    }

    /**
     * 执行分片处理
     *
     * @param shardIndex 分片索引
     * @param shardTotal 分片总数
     * @throws InterruptedException 当线程被中断时抛出
     */
    private void executeSharding(int shardIndex, int shardTotal) throws InterruptedException {
        DataRange range = calculateShardRange(shardIndex, shardTotal);
        log.info("Shard {} data range: [{}, {})", shardIndex, range.getStartIndex(), range.getEndIndex());

        for (int i = range.getStartIndex(); i < range.getEndIndex(); i++) {
            if (i % SHARDING_PROGRESS_INTERVAL == 0) {
                int processed = i - range.getStartIndex();
                int total = range.getEndIndex() - range.getStartIndex();
                log.info("Shard {} progress: {}/{}", shardIndex, processed, total);
            }
            TimeUnit.MILLISECONDS.sleep(SHARDING_DELAY_MS);
        }

        log.info("Shard {} completed, processed {} records", shardIndex, range.getEndIndex() - range.getStartIndex());
    }

    /**
     * 计算分片数据范围
     *
     * @param shardIndex 分片索引
     * @param shardTotal 分片总数
     * @return 数据范围
     */
    private DataRange calculateShardRange(int shardIndex, int shardTotal) {
        int startIndex = shardIndex * (SHARDING_TOTAL_DATA / shardTotal);
        int endIndex = (shardIndex + 1) * (SHARDING_TOTAL_DATA / shardTotal);

        // 最后一个分片处理剩余所有数据
        if (shardIndex == shardTotal - 1) {
            endIndex = SHARDING_TOTAL_DATA;
        }

        return new DataRange(startIndex, endIndex);
    }

    /**
     * 获取分片数据量
     *
     * @param shardIndex 分片索引
     * @param shardTotal 分片总数
     * @return 数据量
     */
    private int getShardDataCount(int shardIndex, int shardTotal) {
        DataRange range = calculateShardRange(shardIndex, shardTotal);
        return range.getEndIndex() - range.getStartIndex();
    }

    /**
     * 解析整数参数
     *
     * @param json         JSON 字符串
     * @param key          键
     * @param defaultValue 默认值
     * @return 参数值
     */
    private int getIntParam(String json, String key, int defaultValue) {
        String valueStr = extractJsonValue(json, key, false);
        if (valueStr != null) {
            try {
                return Integer.parseInt(valueStr.trim());
            } catch (NumberFormatException e) {
                log.warn("Failed to parse parameter {}, using default: {}", key, defaultValue);
            }
        }
        return defaultValue;
    }

    /**
     * 解析字符串参数
     *
     * @param json         JSON 字符串
     * @param key          键
     * @param defaultValue 默认值
     * @return 参数值
     */
    private String getStringParam(String json, String key, String defaultValue) {
        String valueStr = extractJsonValue(json, key, true);
        return valueStr != null ? valueStr : defaultValue;
    }

    /**
     * 从 JSON 中提取值
     *
     * @param json         JSON 字符串
     * @param key          键
     * @param isStringValue 是否为字符串值
     * @return 提取的值
     */
    private String extractJsonValue(String json, String key, boolean isStringValue) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            String searchPattern = "\"" + key + "\":" + (isStringValue ? "\"" : "");
            int start = json.indexOf(searchPattern);
            if (start != -1) {
                start += searchPattern.length();
                int end = isStringValue
                        ? json.indexOf("\"", start)
                        : findJsonValueEnd(json, start);
                if (end != -1) {
                    return json.substring(start, end).trim();
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract parameter {}, using default", key);
        }
        return null;
    }

    /**
     * 查找 JSON 值的结束位置
     *
     * @param json    JSON 字符串
     * @param start   起始位置
     * @return 结束位置
     */
    private int findJsonValueEnd(String json, int start) {
        int commaIndex = json.indexOf(",", start);
        int braceIndex = json.indexOf("}", start);
        if (commaIndex == -1) {
            return braceIndex;
        }
        if (braceIndex == -1) {
            return commaIndex;
        }
        return Math.min(commaIndex, braceIndex);
    }
}
