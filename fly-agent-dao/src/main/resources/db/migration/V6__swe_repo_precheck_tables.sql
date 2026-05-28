CREATE TABLE IF NOT EXISTS swe_repo_blacklist (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo VARCHAR(300) NOT NULL COMMENT 'Normalized GitHub repo owner/name',
    github_url VARCHAR(500) COMMENT 'GitHub URL',
    github_stars INT COMMENT 'GitHub stars captured by source file',
    benchmarks VARCHAR(1000) COMMENT 'Benchmark names',
    datasets TEXT COMMENT 'Dataset names',
    splits TEXT COMMENT 'Dataset splits',
    instance_count INT COMMENT 'Instance count in existing datasets',
    languages VARCHAR(1000) COMMENT 'Language distribution from source file',
    example_instance_id VARCHAR(300) COMMENT 'Example dataset instance id',
    example_base_commit VARCHAR(100) COMMENT 'Example base commit',
    source_file VARCHAR(1000) COMMENT 'Imported source file',
    source_sheet VARCHAR(100) COMMENT 'Imported source sheet',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_swe_repo_blacklist_repo (repo),
    INDEX idx_swe_repo_blacklist_stars (github_stars),
    INDEX idx_swe_repo_blacklist_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE existing dataset repo blacklist';

CREATE TABLE IF NOT EXISTS swe_repo_sca_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    repo VARCHAR(300) NOT NULL COMMENT 'Normalized GitHub repo owner/name',
    tool_name VARCHAR(100) NOT NULL COMMENT 'SCA tool/source name',
    license_spdx_id VARCHAR(100) COMMENT 'Detected repo license SPDX id',
    license_name VARCHAR(300) COMMENT 'Detected repo license name',
    compatibility_status VARCHAR(40) NOT NULL COMMENT 'ALLOW/REJECT/UNKNOWN',
    compatibility_reason VARCHAR(1000) COMMENT 'Commercial AI training compatibility reason',
    component_count INT DEFAULT 0 COMMENT 'Number of components in the SCA report',
    report_json JSON COMMENT 'Software composition analysis report',
    raw_json JSON COMMENT 'Raw SCA upstream payload',
    checked_at DATETIME NOT NULL COMMENT 'SCA checked time',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_swe_repo_sca_report_repo (repo),
    INDEX idx_swe_repo_sca_report_status (compatibility_status),
    INDEX idx_swe_repo_sca_report_license (license_spdx_id),
    INDEX idx_swe_repo_sca_report_checked_at (checked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE repo SCA license compatibility report';

CREATE TABLE IF NOT EXISTS swe_repo_scan_cursor (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cursor_key VARCHAR(300) NOT NULL COMMENT 'language/keyword/star-range cursor key',
    language VARCHAR(100) NOT NULL COMMENT 'GitHub primary language',
    keyword VARCHAR(200) COMMENT 'GitHub search keyword',
    min_stars INT NOT NULL COMMENT 'Configured lower star bound',
    initial_max_stars INT COMMENT 'Configured upper star bound',
    current_max_stars INT COMMENT 'Next scan upper star bound',
    last_min_seen_stars INT COMMENT 'Lowest star count seen in the last scan',
    exhausted TINYINT(1) DEFAULT 0 COMMENT 'Whether the configured star range is exhausted',
    last_summary VARCHAR(1000) COMMENT 'Last scan summary',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_swe_repo_scan_cursor_key (cursor_key),
    INDEX idx_swe_repo_scan_cursor_language (language),
    INDEX idx_swe_repo_scan_cursor_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE repo discovery star cursor';
