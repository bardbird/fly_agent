ALTER TABLE swe_task
    ADD COLUMN candidate_id BIGINT NULL COMMENT '关联候选PR ID' AFTER id,
    ADD INDEX idx_swe_task_candidate_id (candidate_id);

UPDATE swe_task task
JOIN swe_candidate candidate ON candidate.pr_url = task.source_url
SET task.candidate_id = candidate.id
WHERE task.candidate_id IS NULL;

ALTER TABLE swe_pipeline_run
    ADD COLUMN candidate_id BIGINT NULL COMMENT '关联候选PR ID' AFTER task_id,
    ADD INDEX idx_swe_run_candidate_id (candidate_id);

UPDATE swe_pipeline_run run
JOIN swe_task task ON task.id = run.task_id
SET run.candidate_id = task.candidate_id
WHERE run.candidate_id IS NULL
  AND task.candidate_id IS NOT NULL;
