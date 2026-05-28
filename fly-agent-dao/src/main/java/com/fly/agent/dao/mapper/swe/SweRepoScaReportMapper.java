package com.fly.agent.dao.mapper.swe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.swe.SweRepoScaReportEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for SWE-Pro repository SCA/license reports.
 */
public interface SweRepoScaReportMapper extends BaseMapper<SweRepoScaReportEntity> {

    @Insert("""
            INSERT INTO swe_repo_sca_report (
                repo,
                primary_language,
                github_stars,
                search_keyword,
                search_min_stars,
                search_max_stars,
                tool_name,
                license_spdx_id,
                license_name,
                compatibility_status,
                compatibility_reason,
                component_count,
                report_json,
                raw_json,
                checked_at
            ) VALUES (
                #{entity.repo},
                #{entity.primaryLanguage},
                #{entity.githubStars},
                #{entity.searchKeyword},
                #{entity.searchMinStars},
                #{entity.searchMaxStars},
                #{entity.toolName},
                #{entity.licenseSpdxId},
                #{entity.licenseName},
                #{entity.compatibilityStatus},
                #{entity.compatibilityReason},
                #{entity.componentCount},
                #{entity.reportJson},
                #{entity.rawJson},
                #{entity.checkedAt}
            )
            ON DUPLICATE KEY UPDATE
                primary_language = COALESCE(VALUES(primary_language), primary_language),
                github_stars = COALESCE(VALUES(github_stars), github_stars),
                search_keyword = VALUES(search_keyword),
                search_min_stars = COALESCE(VALUES(search_min_stars), search_min_stars),
                search_max_stars = COALESCE(VALUES(search_max_stars), search_max_stars),
                tool_name = VALUES(tool_name),
                license_spdx_id = VALUES(license_spdx_id),
                license_name = VALUES(license_name),
                compatibility_status = VALUES(compatibility_status),
                compatibility_reason = VALUES(compatibility_reason),
                component_count = VALUES(component_count),
                report_json = VALUES(report_json),
                raw_json = VALUES(raw_json),
                checked_at = VALUES(checked_at),
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("entity") SweRepoScaReportEntity entity);

    @Select("""
            SELECT COUNT(1)
            FROM swe_repo_sca_report s
            WHERE s.primary_language = #{language}
              AND s.search_keyword <=> #{keyword}
              AND (#{minStars} IS NULL OR s.github_stars >= #{minStars})
              AND (#{maxStars} IS NULL OR s.github_stars <= #{maxStars})
            """)
    int countReposInScanScope(
            @Param("language") String language,
            @Param("keyword") String keyword,
            @Param("minStars") Integer minStars,
            @Param("maxStars") Integer maxStars);

    @Select("""
            SELECT s.repo
            FROM swe_repo_sca_report s
            LEFT JOIN swe_repo_blacklist b ON b.repo = s.repo
            LEFT JOIN (
                SELECT DISTINCT repo
                FROM swe_candidate
            ) c ON c.repo = s.repo
            WHERE s.compatibility_status = 'ALLOW'
              AND b.repo IS NULL
              AND c.repo IS NULL
            ORDER BY s.checked_at DESC, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> selectAllowedReposForCandidateScan(@Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            SELECT s.repo
            FROM swe_repo_sca_report s
            LEFT JOIN swe_repo_blacklist b ON b.repo = s.repo
            WHERE s.compatibility_status = 'ALLOW'
              AND b.repo IS NULL
              AND s.primary_language = #{language}
              AND s.search_keyword <=> #{keyword}
              AND (#{minStars} IS NULL OR s.github_stars >= #{minStars})
              AND (#{maxStars} IS NULL OR s.github_stars <= #{maxStars})
            ORDER BY s.github_stars DESC, s.checked_at DESC, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> selectAllowedReposInScanScope(
            @Param("language") String language,
            @Param("keyword") String keyword,
            @Param("minStars") Integer minStars,
            @Param("maxStars") Integer maxStars,
            @Param("limit") int limit,
            @Param("offset") int offset);
}
