ALTER TABLE swe_repo_sca_report
    ADD COLUMN candidate_last_scanned_at DATETIME NULL COMMENT 'Last candidate backfill scan attempt time' AFTER checked_at,
    ADD INDEX idx_swe_repo_sca_report_candidate_scan (primary_language, candidate_last_scanned_at);
