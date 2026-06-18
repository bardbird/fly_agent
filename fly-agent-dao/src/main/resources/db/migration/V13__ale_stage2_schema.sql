ALTER TABLE ale_run
    ADD COLUMN stage2_status VARCHAR(40) NULL COMMENT 'Stage2 状态' AFTER status,
    ADD COLUMN stage2_progress INT NOT NULL DEFAULT 0 COMMENT 'Stage2 进度百分比' AFTER stage2_status,
    ADD COLUMN stage2_summary_path VARCHAR(1000) NULL COMMENT 'Stage2 summary.json路径' AFTER summary_path,
    ADD COLUMN stage2_started_at DATETIME NULL COMMENT 'Stage2 开始时间' AFTER finished_at,
    ADD COLUMN stage2_finished_at DATETIME NULL COMMENT 'Stage2 结束时间' AFTER stage2_started_at;

ALTER TABLE ale_task
    ADD COLUMN stage2_status VARCHAR(40) NULL COMMENT 'Stage2 状态' AFTER status,
    ADD COLUMN stage2_score DECIMAL(6,3) NULL COMMENT 'Stage2 评分 (0~1)' AFTER score,
    ADD COLUMN stage2_duration_s DECIMAL(10,1) NULL COMMENT 'Stage2 执行耗时(秒)' AFTER stage2_score,
    ADD COLUMN stage2_result_dir VARCHAR(1000) NULL COMMENT 'Stage2 result目录' AFTER evidence_path,
    ADD COLUMN stage2_error TEXT NULL COMMENT 'Stage2 错误信息' AFTER error_message;
