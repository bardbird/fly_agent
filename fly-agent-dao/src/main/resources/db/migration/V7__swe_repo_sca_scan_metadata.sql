ALTER TABLE swe_repo_sca_report
    ADD COLUMN primary_language VARCHAR(100) NULL COMMENT 'GitHub primary language used for repository discovery' AFTER repo,
    ADD COLUMN github_stars INT NULL COMMENT 'GitHub stars captured during repository discovery' AFTER primary_language,
    ADD COLUMN search_keyword VARCHAR(200) NULL COMMENT 'Repository search keyword used for discovery' AFTER github_stars,
    ADD COLUMN search_min_stars INT NULL COMMENT 'Repository search lower star bound' AFTER search_keyword,
    ADD COLUMN search_max_stars INT NULL COMMENT 'Repository search upper star bound' AFTER search_min_stars,
    ADD INDEX idx_swe_repo_sca_report_language_stars (primary_language, github_stars),
    ADD INDEX idx_swe_repo_sca_report_scan_scope (primary_language, search_keyword, search_min_stars, search_max_stars);
