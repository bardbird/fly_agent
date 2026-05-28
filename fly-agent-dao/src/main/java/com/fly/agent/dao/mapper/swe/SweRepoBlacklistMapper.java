package com.fly.agent.dao.mapper.swe;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.swe.SweRepoBlacklistEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/**
 * Mapper for SWE-Pro repository denylist entries.
 */
public interface SweRepoBlacklistMapper extends BaseMapper<SweRepoBlacklistEntity> {

    @Insert("""
            INSERT INTO swe_repo_blacklist (
                repo,
                github_url,
                github_stars,
                benchmarks,
                datasets,
                splits,
                instance_count,
                languages,
                example_instance_id,
                example_base_commit,
                source_file,
                source_sheet
            ) VALUES (
                #{entity.repo},
                #{entity.githubUrl},
                #{entity.githubStars},
                #{entity.benchmarks},
                #{entity.datasets},
                #{entity.splits},
                #{entity.instanceCount},
                #{entity.languages},
                #{entity.exampleInstanceId},
                #{entity.exampleBaseCommit},
                #{entity.sourceFile},
                #{entity.sourceSheet}
            )
            ON DUPLICATE KEY UPDATE
                github_url = VALUES(github_url),
                github_stars = VALUES(github_stars),
                benchmarks = VALUES(benchmarks),
                datasets = VALUES(datasets),
                splits = VALUES(splits),
                instance_count = VALUES(instance_count),
                languages = VALUES(languages),
                example_instance_id = VALUES(example_instance_id),
                example_base_commit = VALUES(example_base_commit),
                source_file = VALUES(source_file),
                source_sheet = VALUES(source_sheet),
                updated_at = CURRENT_TIMESTAMP
            """)
    int upsert(@Param("entity") SweRepoBlacklistEntity entity);
}
