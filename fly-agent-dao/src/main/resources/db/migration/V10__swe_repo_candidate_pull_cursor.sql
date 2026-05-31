ALTER TABLE swe_repo_sca_report
    ADD COLUMN candidate_next_pull_page INT NOT NULL DEFAULT 1 COMMENT 'Next closed PR page for candidate backfill' AFTER candidate_last_scanned_at,
    ADD COLUMN candidate_pull_exhausted_at DATETIME NULL COMMENT 'Last time candidate backfill reached the end of closed PR pages' AFTER candidate_next_pull_page,
    ADD INDEX idx_swe_repo_sca_report_candidate_cursor (
        compatibility_status,
        primary_language,
        search_keyword,
        candidate_last_scanned_at,
        candidate_next_pull_page
    );
