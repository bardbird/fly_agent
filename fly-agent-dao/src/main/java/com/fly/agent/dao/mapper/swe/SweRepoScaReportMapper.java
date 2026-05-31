package com.fly.agent.dao.mapper.swe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.swe.SweRepoScaReportEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
            SELECT COUNT(1)
            FROM swe_repo_sca_report s
            WHERE s.primary_language = #{language}
              AND s.search_keyword <=> #{keyword}
              AND (#{minStars} IS NULL OR s.github_stars >= #{minStars})
              AND (#{maxStars} IS NULL OR s.github_stars <= #{maxStars})
              AND DATE(s.checked_at) = #{scanDate}
            """)
    int countReposCheckedOnDateInScanScope(
            @Param("language") String language,
            @Param("keyword") String keyword,
            @Param("minStars") Integer minStars,
            @Param("maxStars") Integer maxStars,
            @Param("scanDate") LocalDate scanDate);

    @Select("""
            SELECT COUNT(1)
            FROM swe_repo_sca_report s
            WHERE s.compatibility_status = 'ALLOW'
              AND s.primary_language = #{language}
              AND s.search_keyword <=> #{keyword}
              AND (#{minStars} IS NULL OR s.github_stars >= #{minStars})
              AND (#{maxStars} IS NULL OR s.github_stars <= #{maxStars})
              AND DATE(s.candidate_last_scanned_at) = #{scanDate}
            """)
    int countCandidateScannedOnDateInScanScope(
            @Param("language") String language,
            @Param("keyword") String keyword,
            @Param("minStars") Integer minStars,
            @Param("maxStars") Integer maxStars,
            @Param("scanDate") LocalDate scanDate);

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
              AND (s.candidate_last_scanned_at IS NULL OR DATE(s.candidate_last_scanned_at) <> #{scanDate})
            ORDER BY s.github_stars DESC, s.checked_at DESC, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<String> selectAllowedReposInScanScope(
            @Param("language") String language,
            @Param("keyword") String keyword,
            @Param("minStars") Integer minStars,
            @Param("maxStars") Integer maxStars,
            @Param("scanDate") LocalDate scanDate,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Update("""
            UPDATE swe_repo_sca_report
            SET candidate_last_scanned_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE repo = #{repo}
            """)
    int markCandidateScanAttempt(@Param("repo") String repo);

    @Select("""
            <script>
            SELECT COUNT(1)
            FROM swe_repo_sca_report s
            WHERE s.compatibility_status = 'ALLOW'
            <if test="language != null and language != ''">
              <choose>
                <when test="language == 'unknown'">
                  AND s.primary_language IS NULL
                </when>
                <otherwise>
                  AND s.primary_language = #{language}
                </otherwise>
              </choose>
            </if>
            <if test="inCandidate != null">
              AND <choose>
                    <when test="inCandidate">
                      EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo)
                    </when>
                    <otherwise>
                      NOT EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo)
                    </otherwise>
                  </choose>
            </if>
            <if test="checkedFrom != null">
              AND s.checked_at &gt;= #{checkedFrom}
            </if>
            <if test="checkedToExclusive != null">
              AND s.checked_at &lt; #{checkedToExclusive}
            </if>
            </script>
            """)
    long countAllowedRepoReports(
            @Param("language") String language,
            @Param("inCandidate") Boolean inCandidate,
            @Param("checkedFrom") LocalDateTime checkedFrom,
            @Param("checkedToExclusive") LocalDateTime checkedToExclusive);

    @Select("""
            <script>
            SELECT
                s.id AS id,
                s.repo AS repo,
                s.primary_language AS primaryLanguage,
                s.github_stars AS githubStars,
                s.license_spdx_id AS licenseSpdxId,
                s.license_name AS licenseName,
                s.compatibility_reason AS compatibilityReason,
                s.checked_at AS checkedAt,
                s.candidate_last_scanned_at AS candidateLastScannedAt,
                CASE WHEN EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo) THEN 1 ELSE 0 END AS inCandidate
            FROM swe_repo_sca_report s
            WHERE s.compatibility_status = 'ALLOW'
            <if test="language != null and language != ''">
              <choose>
                <when test="language == 'unknown'">
                  AND s.primary_language IS NULL
                </when>
                <otherwise>
                  AND s.primary_language = #{language}
                </otherwise>
              </choose>
            </if>
            <if test="inCandidate != null">
              AND <choose>
                    <when test="inCandidate">
                      EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo)
                    </when>
                    <otherwise>
                      NOT EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo)
                    </otherwise>
                  </choose>
            </if>
            <if test="checkedFrom != null">
              AND s.checked_at &gt;= #{checkedFrom}
            </if>
            <if test="checkedToExclusive != null">
              AND s.checked_at &lt; #{checkedToExclusive}
            </if>
            ORDER BY s.checked_at DESC, s.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Map<String, Object>> selectAllowedRepoReports(
            @Param("language") String language,
            @Param("inCandidate") Boolean inCandidate,
            @Param("checkedFrom") LocalDateTime checkedFrom,
            @Param("checkedToExclusive") LocalDateTime checkedToExclusive,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT
                s.repo AS repo,
                CASE WHEN EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo) THEN 1 ELSE 0 END AS inCandidate
            FROM swe_repo_sca_report s
            WHERE s.compatibility_status = 'ALLOW'
            <if test="language != null and language != ''">
              <choose>
                <when test="language == 'unknown'">
                  AND s.primary_language IS NULL
                </when>
                <otherwise>
                  AND s.primary_language = #{language}
                </otherwise>
              </choose>
            </if>
            <if test="inCandidate != null">
              AND <choose>
                    <when test="inCandidate">
                      EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo)
                    </when>
                    <otherwise>
                      NOT EXISTS (SELECT 1 FROM swe_candidate c WHERE c.repo = s.repo)
                    </otherwise>
                  </choose>
            </if>
            <if test="checkedFrom != null">
              AND s.checked_at &gt;= #{checkedFrom}
            </if>
            <if test="checkedToExclusive != null">
              AND s.checked_at &lt; #{checkedToExclusive}
            </if>
            ORDER BY s.checked_at DESC, s.id DESC
            </script>
            """)
    List<Map<String, Object>> selectAllowedRepoExportRows(
            @Param("language") String language,
            @Param("inCandidate") Boolean inCandidate,
            @Param("checkedFrom") LocalDateTime checkedFrom,
            @Param("checkedToExclusive") LocalDateTime checkedToExclusive);
}
