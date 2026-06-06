ALTER TABLE swe_repo_scan_cursor
    ADD COLUMN current_page INT NOT NULL DEFAULT 1 COMMENT 'Next GitHub search page within the current star bucket' AFTER current_max_stars;
