package com.fly.agent.dao.mapper.swe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.swe.SweRepoScanCursorEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper for resumable SWE-Pro repository scan cursors.
 */
public interface SweRepoScanCursorMapper extends BaseMapper<SweRepoScanCursorEntity> {

    @Insert("""
            INSERT IGNORE INTO swe_repo_scan_cursor (
                cursor_key,
                language,
                keyword,
                min_stars,
                initial_max_stars,
                current_max_stars,
                exhausted
            ) VALUES (
                #{entity.cursorKey},
                #{entity.language},
                #{entity.keyword},
                #{entity.minStars},
                #{entity.initialMaxStars},
                #{entity.currentMaxStars},
                #{entity.exhausted}
            )
            """)
    int insertIgnoreInitial(@Param("entity") SweRepoScanCursorEntity entity);

    @Insert("""
            INSERT INTO swe_repo_scan_cursor (
                cursor_key,
                language,
                keyword,
                min_stars,
                initial_max_stars,
                current_max_stars,
                last_min_seen_stars,
                exhausted,
                last_summary
            ) VALUES (
                #{entity.cursorKey},
                #{entity.language},
                #{entity.keyword},
                #{entity.minStars},
                #{entity.initialMaxStars},
                #{entity.currentMaxStars},
                #{entity.lastMinSeenStars},
                #{entity.exhausted},
                #{entity.lastSummary}
            )
            ON DUPLICATE KEY UPDATE
                current_max_stars = VALUES(current_max_stars),
                last_min_seen_stars = VALUES(last_min_seen_stars),
                exhausted = VALUES(exhausted),
                last_summary = VALUES(last_summary),
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsertProgress(@Param("entity") SweRepoScanCursorEntity entity);

    @Delete("DELETE FROM swe_repo_scan_cursor WHERE cursor_key = #{cursorKey}")
    int deleteByCursorKey(@Param("cursorKey") String cursorKey);
}
