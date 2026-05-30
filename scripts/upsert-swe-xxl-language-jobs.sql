-- Upsert SWE language jobs into an XXL-Job 2.4.x database.
-- Replace @github_token before enabling these jobs. githubToken is required by the handlers.

SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET @executor_appname = 'fly-agent-executor' COLLATE utf8mb4_0900_ai_ci;
SET @github_token = 'REPLACE_WITH_GITHUB_TOKEN' COLLATE utf8mb4_0900_ai_ci;
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
    cron_minute INT NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_swe_languages (language, cron_minute) VALUES
    ('c', 0),
    ('c++', 5),
    ('ruby', 10),
    ('rust', 15),
    ('go', 20),
    ('javascript', 25),
    ('php', 30),
    ('typescript', 35),
    ('python', 40),
    ('java', 45);

UPDATE xxl_job_info j
JOIN tmp_swe_languages l
  ON JSON_VALID(j.executor_param)
 AND JSON_CONTAINS(JSON_EXTRACT(j.executor_param, '$.languages'), JSON_QUOTE(l.language))
SET j.job_desc = CONCAT('SWE SCA Discovery - ', l.language),
    j.update_time = NOW(),
    j.executor_param = JSON_OBJECT(
        'githubToken', @github_token,
        'languages', JSON_ARRAY(l.language)
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.cron_minute, ' 1 * * ?'),
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
        'githubToken', @github_token,
        'languages', JSON_ARRAY(l.language)
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.cron_minute, ' 3 * * ?'),
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
    CONCAT('0 ', cron_minute, ' 1 * * ?'),
    'DO_NOTHING',
    'ROUND',
    'sweRepoScaDiscoveryJob',
    JSON_OBJECT(
        'githubToken', @github_token,
        'languages', JSON_ARRAY(language)
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
        'githubToken', @github_token,
        'languages', JSON_ARRAY(l.language)
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.cron_minute, ' 1 * * ?'),
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
    CONCAT('0 ', cron_minute, ' 3 * * ?'),
    'DO_NOTHING',
    'ROUND',
    'sweRepoCandidateBackfillJob',
    JSON_OBJECT(
        'githubToken', @github_token,
        'languages', JSON_ARRAY(language)
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
        'githubToken', @github_token,
        'languages', JSON_ARRAY(l.language)
    ),
    j.schedule_type = 'CRON',
    j.schedule_conf = CONCAT('0 ', l.cron_minute, ' 3 * * ?'),
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
