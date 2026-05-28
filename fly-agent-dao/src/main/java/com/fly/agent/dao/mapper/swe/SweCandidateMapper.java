package com.fly.agent.dao.mapper.swe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.swe.SweCandidateEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for persisted SWE-Pro PR candidates.
 */
public interface SweCandidateMapper extends BaseMapper<SweCandidateEntity> {

    @Select("""
            SELECT *
            FROM swe_candidate
            WHERE pr_url = #{prUrl}
            LIMIT 1
            FOR UPDATE
            """)
    SweCandidateEntity selectByPrUrlForUpdate(@Param("prUrl") String prUrl);

    @Select("""
            SELECT COUNT(1)
            FROM swe_candidate
            WHERE repo = #{repo}
              AND issue_numbers IS NOT NULL
              AND issue_numbers <> ''
              AND test_patch_present = 1
              AND (duplicate_status IS NULL OR duplicate_status <> 'DELIVERED')
            """)
    int countQualifiedIssueCandidatesByRepo(@Param("repo") String repo);
}
