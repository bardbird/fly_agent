package com.fly.agent.dao.entity.swe;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Resumable star-range cursor for SWE-Pro repository discovery.
 */
@Data
@TableName("swe_repo_scan_cursor")
public class SweRepoScanCursorEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String cursorKey;

    private String language;

    private String keyword;

    private Integer minStars;

    private Integer initialMaxStars;

    private Integer currentMaxStars;

    private Integer lastMinSeenStars;

    private Boolean exhausted;

    private String lastSummary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
