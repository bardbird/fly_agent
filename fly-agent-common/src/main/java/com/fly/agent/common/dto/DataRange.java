package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据范围DTO
 * 用于分片任务的数据范围定义
 *
 * @param startIndex 起始索引
 * @param endIndex 结束索引
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataRange {

    /**
     * 起始索引
     */
    private int startIndex;

    /**
     * 结束索引
     */
    private int endIndex;
}
