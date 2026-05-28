ALTER TABLE swe_candidate
    ADD COLUMN issue_url VARCHAR(500) NULL COMMENT 'Primary linked GitHub issue URL' AFTER pr_url,
    ADD COLUMN issue_numbers VARCHAR(500) NULL COMMENT 'Resolved issue numbers JSON' AFTER issue_url,
    ADD COLUMN problem_statement MEDIUMTEXT NULL COMMENT 'Issue-grounded problem statement' AFTER issue_numbers,
    ADD COLUMN hints_text MEDIUMTEXT NULL COMMENT 'Pre-solution issue hints' AFTER problem_statement,
    ADD COLUMN test_patch_present TINYINT(1) DEFAULT 0 COMMENT 'Whether PR contains test patch files' AFTER test_total_changed,
    ADD COLUMN fail_to_pass TEXT NULL COMMENT 'FAIL_TO_PASS test cases JSON' AFTER test_patch_present,
    ADD COLUMN pass_to_pass TEXT NULL COMMENT 'PASS_TO_PASS test cases JSON' AFTER fail_to_pass,
    ADD COLUMN benchmark_status VARCHAR(40) DEFAULT 'UNKNOWN' COMMENT 'Public benchmark duplicate status' AFTER pass_to_pass,
    ADD COLUMN failed_history_status VARCHAR(40) DEFAULT 'UNKNOWN' COMMENT 'Historical failed candidate status' AFTER benchmark_status;
