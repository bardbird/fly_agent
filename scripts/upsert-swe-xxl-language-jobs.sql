-- Upsert SWE language jobs into an XXL-Job 2.4.x database.
-- GitHub keys are managed by the Redis-backed key pool; job params do not include githubToken.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET @executor_appname = 'fly-agent-executor' COLLATE utf8mb4_0900_ai_ci;
SET @candidate_pull_limit = 5;
SET @candidate_pull_per_page = 30;
SET @candidate_pull_pages_per_repo = 5;
SET @candidate_min_gold_source_files = 5;
SET @candidate_max_gold_source_files = 100;
SET @candidate_min_gold_lines = 108;
SET @candidate_max_gold_lines = 1000;
SET @job_group_id = (
    SELECT id
    FROM xxl_job_group
    WHERE app_name = @executor_appname
    ORDER BY id
    LIMIT 1
);

DROP TEMPORARY TABLE IF EXISTS tmp_swe_languages;
CREATE TEMPORARY TABLE tmp_swe_languages (
    language VARCHAR(40) COLLATE utf8mb4_0900_ai_ci PRIMARY KEY,
    sca_cron_minute INT NOT NULL,
    candidate_cron_minute INT NOT NULL,
    min_stars INT NOT NULL,
    max_stars INT NOT NULL,
    sca_daily_repo_limit INT NOT NULL,
    sca_per_run_repo_limit INT NOT NULL,
    sca_repository_pages INT NOT NULL,
    sca_repository_per_page INT NOT NULL,
    candidate_daily_repo_limit INT NOT NULL,
    candidate_per_run_repo_limit INT NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_swe_languages (
    language,
    sca_cron_minute,
    candidate_cron_minute,
    min_stars,
    max_stars,
    sca_daily_repo_limit,
    sca_per_run_repo_limit,
    sca_repository_pages,
    sca_repository_per_page,
    candidate_daily_repo_limit,
    candidate_per_run_repo_limit
) VALUES
    ('c', 0, 2, 500, 5000, 2000, 100, 10, 50, 120, 5),
    ('c++', 3, 7, 500, 5000, 2000, 100, 10, 50, 120, 5),
    ('ruby', 6, 12, 500, 5000, 2000, 100, 10, 50, 120, 5),
    ('rust', 9, 17, 500, 5000, 6000, 300, 10, 50, 300, 15),
    ('go', 13, 22, 500, 5000, 10000, 500, 10, 50, 480, 20),
    ('javascript', 16, 27, 500, 5000, 7500, 375, 10, 50, 360, 15),
    ('php', 19, 32, 500, 5000, 4000, 200, 10, 50, 240, 10),
    ('typescript', 23, 37, 500, 5000, 7500, 375, 10, 50, 360, 15),
    ('python', 26, 42, 500, 5000, 10000, 500, 10, 50, 480, 20),
    ('java', 29, 47, 500, 5000, 5000, 250, 10, 50, 240, 10);

UPDATE xxl_job_info j
JOIN tmp_swe_languages l
  ON JSON_VALID(j.executor_param)
 AND JSON_CONTAINS(JSON_EXTRACT(j.executor_param, '$.languages'), JSON_QUOTE(l.language))
SET j.job_desc = CONCAT('SWE SCA Discovery - ', l.language),
    j.update_time = NOW(),
    j.executor_param = JSON_OBJECT(
        'languages', JSON_ARRAY(l.language),
        'minStars', l.min_stars,
        'maxStars', l.max_stars,
        'dailyRepoLimit', l.sca_daily_repo_limit,
        'perRunRepoLimit', l.sca_per_run_repo_limit,
        'repositoryPages', l.sca_repository_pages,
        'repositoryPerPage', l.sca_repository_per_page,
        'profileFilterEnabled', true,
        'minPrimaryLanguageRatio', 0.50,
        'maxLanguageCount', 8,
        'maxDirectDependencies', 80,
        'maxManifestCount', 20,
        'maxManifestDownloads', 3,
        'useStarCursor', true
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.sca_cron_minute, ',', l.sca_cron_minute + 30, ' * * * ?'),
    j.executor_block_strategy = 'SERIAL_EXECUTION'
WHERE @job_group_id IS NOT NULL
  AND j.job_group = @job_group_id
  AND j.executor_handler = 'sweRepoScaDiscoveryJob';

UPDATE xxl_job_info j
JOIN tmp_swe_languages l
  ON JSON_VALID(j.executor_param)
 AND JSON_CONTAINS(JSON_EXTRACT(j.executor_param, '$.languages'), JSON_QUOTE(l.language))
SET j.job_desc = CONCAT('SWE Candidate Backfill - ', l.language),
    j.update_time = NOW(),
    j.executor_param = JSON_OBJECT(
        'languages', JSON_ARRAY(l.language),
        'dailyRepoLimit', l.candidate_daily_repo_limit,
        'perRunRepoLimit', l.candidate_per_run_repo_limit,
        'pullLimit', @candidate_pull_limit,
        'pullPerPage', @candidate_pull_per_page,
        'pullPagesPerRepo', @candidate_pull_pages_per_repo,
        'minGoldSourceFiles', @candidate_min_gold_source_files,
        'maxGoldSourceFiles', @candidate_max_gold_source_files,
        'minGoldLines', @candidate_min_gold_lines,
        'maxGoldLines', @candidate_max_gold_lines
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.candidate_cron_minute, ' * * * ?'),
    j.executor_block_strategy = 'SERIAL_EXECUTION'
WHERE @job_group_id IS NOT NULL
  AND j.job_group = @job_group_id
  AND j.executor_handler = 'sweRepoCandidateBackfillJob';

INSERT INTO xxl_job_info (
    job_group,
    job_desc,
    add_time,
    update_time,
    author,
    alarm_email,
    schedule_type,
    schedule_conf,
    misfire_strategy,
    executor_route_strategy,
    executor_handler,
    executor_param,
    executor_block_strategy,
    executor_timeout,
    executor_fail_retry_count,
    glue_type,
    glue_source,
    glue_remark,
    glue_updatetime,
    child_jobid,
    trigger_status,
    trigger_last_time,
    trigger_next_time
)
SELECT
    @job_group_id,
    CONCAT('SWE SCA Discovery - ', language),
    NOW(),
    NOW(),
    'fly-agent',
    '',
    'CRON',
    CONCAT('0 ', sca_cron_minute, ',', sca_cron_minute + 30, ' * * * ?'),
    'DO_NOTHING',
    'ROUND',
    'sweRepoScaDiscoveryJob',
    JSON_OBJECT(
        'languages', JSON_ARRAY(language),
        'minStars', l.min_stars,
        'maxStars', l.max_stars,
        'dailyRepoLimit', l.sca_daily_repo_limit,
        'perRunRepoLimit', l.sca_per_run_repo_limit,
        'repositoryPages', l.sca_repository_pages,
        'repositoryPerPage', l.sca_repository_per_page,
        'profileFilterEnabled', true,
        'minPrimaryLanguageRatio', 0.50,
        'maxLanguageCount', 8,
        'maxDirectDependencies', 80,
        'maxManifestCount', 20,
        'maxManifestDownloads', 3,
        'useStarCursor', true
    ),
    'SERIAL_EXECUTION',
    0,
    0,
    'BEAN',
    '',
    'GLUE代码初始化',
    NOW(),
    '',
    0,
    0,
    0
FROM tmp_swe_languages l
WHERE @job_group_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM xxl_job_info j
      WHERE j.job_group = @job_group_id
        AND j.executor_handler = 'sweRepoScaDiscoveryJob'
        AND j.job_desc = CONCAT('SWE SCA Discovery - ', l.language)
  );

UPDATE xxl_job_info j
JOIN tmp_swe_languages l
  ON j.job_desc = CONCAT('SWE SCA Discovery - ', l.language)
SET j.update_time = NOW(),
    j.executor_param = JSON_OBJECT(
        'languages', JSON_ARRAY(l.language),
        'minStars', l.min_stars,
        'maxStars', l.max_stars,
        'dailyRepoLimit', l.sca_daily_repo_limit,
        'perRunRepoLimit', l.sca_per_run_repo_limit,
        'repositoryPages', l.sca_repository_pages,
        'repositoryPerPage', l.sca_repository_per_page,
        'profileFilterEnabled', true,
        'minPrimaryLanguageRatio', 0.50,
        'maxLanguageCount', 8,
        'maxDirectDependencies', 80,
        'maxManifestCount', 20,
        'maxManifestDownloads', 3,
        'useStarCursor', true
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.sca_cron_minute, ',', l.sca_cron_minute + 30, ' * * * ?'),
    j.executor_handler = 'sweRepoScaDiscoveryJob',
    j.executor_block_strategy = 'SERIAL_EXECUTION'
WHERE @job_group_id IS NOT NULL
  AND j.job_group = @job_group_id
  AND j.executor_handler = 'sweRepoScaDiscoveryJob';

INSERT INTO xxl_job_info (
    job_group,
    job_desc,
    add_time,
    update_time,
    author,
    alarm_email,
    schedule_type,
    schedule_conf,
    misfire_strategy,
    executor_route_strategy,
    executor_handler,
    executor_param,
    executor_block_strategy,
    executor_timeout,
    executor_fail_retry_count,
    glue_type,
    glue_source,
    glue_remark,
    glue_updatetime,
    child_jobid,
    trigger_status,
    trigger_last_time,
    trigger_next_time
)
SELECT
    @job_group_id,
    CONCAT('SWE Candidate Backfill - ', language),
    NOW(),
    NOW(),
    'fly-agent',
    '',
    'CRON',
    CONCAT('0 ', candidate_cron_minute, ' * * * ?'),
    'DO_NOTHING',
    'ROUND',
    'sweRepoCandidateBackfillJob',
    JSON_OBJECT(
        'languages', JSON_ARRAY(language),
        'dailyRepoLimit', l.candidate_daily_repo_limit,
        'perRunRepoLimit', l.candidate_per_run_repo_limit,
        'pullLimit', @candidate_pull_limit,
        'pullPerPage', @candidate_pull_per_page,
        'pullPagesPerRepo', @candidate_pull_pages_per_repo,
        'minGoldSourceFiles', @candidate_min_gold_source_files,
        'maxGoldSourceFiles', @candidate_max_gold_source_files,
        'minGoldLines', @candidate_min_gold_lines,
        'maxGoldLines', @candidate_max_gold_lines
    ),
    'SERIAL_EXECUTION',
    0,
    0,
    'BEAN',
    '',
    'GLUE代码初始化',
    NOW(),
    '',
    0,
    0,
    0
FROM tmp_swe_languages l
WHERE @job_group_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM xxl_job_info j
      WHERE j.job_group = @job_group_id
        AND j.executor_handler = 'sweRepoCandidateBackfillJob'
        AND j.job_desc = CONCAT('SWE Candidate Backfill - ', l.language)
  );

UPDATE xxl_job_info j
JOIN tmp_swe_languages l
  ON j.job_desc = CONCAT('SWE Candidate Backfill - ', l.language)
SET j.update_time = NOW(),
    j.executor_param = JSON_OBJECT(
        'languages', JSON_ARRAY(l.language),
        'dailyRepoLimit', l.candidate_daily_repo_limit,
        'perRunRepoLimit', l.candidate_per_run_repo_limit,
        'pullLimit', @candidate_pull_limit,
        'pullPerPage', @candidate_pull_per_page,
        'pullPagesPerRepo', @candidate_pull_pages_per_repo,
        'minGoldSourceFiles', @candidate_min_gold_source_files,
        'maxGoldSourceFiles', @candidate_max_gold_source_files,
        'minGoldLines', @candidate_min_gold_lines,
        'maxGoldLines', @candidate_max_gold_lines
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.candidate_cron_minute, ' * * * ?'),
    j.executor_handler = 'sweRepoCandidateBackfillJob',
    j.executor_block_strategy = 'SERIAL_EXECUTION'
WHERE @job_group_id IS NOT NULL
  AND j.job_group = @job_group_id
  AND j.executor_handler = 'sweRepoCandidateBackfillJob';

SELECT
    executor_handler,
    COUNT(*) AS job_count
FROM xxl_job_info
WHERE job_group = @job_group_id
  AND executor_handler IN ('sweRepoScaDiscoveryJob', 'sweRepoCandidateBackfillJob')
GROUP BY executor_handler;
