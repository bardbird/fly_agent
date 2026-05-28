CREATE TABLE IF NOT EXISTS swe_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_name VARCHAR(200) NOT NULL COMMENT 'SWE-Pro任务名称',
    repo VARCHAR(300) NOT NULL COMMENT '代码仓库',
    source_url VARCHAR(500) COMMENT '来源URL',
    base_commit VARCHAR(100) COMMENT '基线commit',
    fix_commit VARCHAR(100) COMMENT '修复commit',
    repo_language VARCHAR(100) COMMENT '主语言',
    issue_specificity VARCHAR(500) COMMENT 'issue_specificity标签',
    issue_categories VARCHAR(500) COMMENT 'issue_categories标签',
    sample_path VARCHAR(1000) COMMENT '样本成品目录',
    status VARCHAR(30) DEFAULT 'CREATED' COMMENT '任务状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_swe_task_repo (repo),
    INDEX idx_swe_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE-Pro任务表';

CREATE TABLE IF NOT EXISTS swe_pipeline_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id BIGINT NOT NULL COMMENT '任务ID',
    status VARCHAR(30) NOT NULL COMMENT '运行状态',
    current_stage VARCHAR(80) COMMENT '当前阶段',
    workspace_path VARCHAR(1000) COMMENT '工作区路径',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始时间',
    finished_at DATETIME COMMENT '结束时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_swe_run_task_id (task_id),
    INDEX idx_swe_run_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE-Pro流水线运行表';

CREATE TABLE IF NOT EXISTS swe_pipeline_stage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL COMMENT '运行ID',
    stage_code VARCHAR(80) NOT NULL COMMENT '阶段编码',
    stage_name VARCHAR(120) NOT NULL COMMENT '阶段名称',
    status VARCHAR(30) NOT NULL COMMENT '阶段状态',
    sort_order INT NOT NULL COMMENT '排序',
    result_summary TEXT COMMENT '结果摘要',
    error_message TEXT COMMENT '错误信息',
    started_at DATETIME COMMENT '开始时间',
    finished_at DATETIME COMMENT '结束时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_swe_stage_run_id (run_id),
    INDEX idx_swe_stage_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE-Pro流水线阶段表';

CREATE TABLE IF NOT EXISTS swe_artifact (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL COMMENT '运行ID',
    artifact_type VARCHAR(80) NOT NULL COMMENT '产物类型',
    artifact_name VARCHAR(300) NOT NULL COMMENT '产物名称',
    artifact_path VARCHAR(1000) NOT NULL COMMENT '产物路径',
    file_size BIGINT COMMENT '文件大小',
    checksum VARCHAR(128) COMMENT '校验和',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_swe_artifact_run_id (run_id),
    INDEX idx_swe_artifact_type (artifact_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='SWE-Pro产物表';
