ALTER TABLE swe_repo_sca_report
    ADD INDEX idx_swe_repo_sca_report_candidate_priority (
        compatibility_status,
        primary_language,
        search_keyword,
        candidate_last_scanned_at,
        github_stars,
        checked_at
    );
