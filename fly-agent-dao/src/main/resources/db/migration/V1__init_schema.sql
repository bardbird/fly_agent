-- Agent配置表
CREATE TABLE IF NOT EXISTS agent_config (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    agent_name VARCHAR(100) UNIQUE NOT NULL COMMENT 'Agent名称',
    agent_type VARCHAR(50) NOT NULL COMMENT 'Agent类型',
    system_prompt TEXT COMMENT '系统提示词',
    llm_config JSON COMMENT 'LLM配置',
    tools JSON COMMENT '工具列表',
    status VARCHAR(20) DEFAULT 'CREATED' COMMENT '状态',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_name (agent_name),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent配置表';

-- 对话会话表
CREATE TABLE IF NOT EXISTS conversation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(100) UNIQUE NOT NULL COMMENT '会话ID',
    agent_id BIGINT NOT NULL COMMENT 'Agent ID',
    user_id VARCHAR(100) COMMENT '用户ID',
    title VARCHAR(200) COMMENT '对话标题',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_session_id (session_id),
    INDEX idx_agent_id (agent_id),
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话会话表';

-- 消息记录表
CREATE TABLE IF NOT EXISTS message (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL COMMENT '对话ID',
    role VARCHAR(20) NOT NULL COMMENT '角色',
    content TEXT COMMENT '消息内容',
    tool_calls JSON COMMENT '工具调用',
    tokens INT COMMENT 'Token数量',
    timestamp DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息记录表';

-- Skill配置表
CREATE TABLE IF NOT EXISTS agent_skill (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_name VARCHAR(100) UNIQUE NOT NULL COMMENT 'Skill名称',
    description VARCHAR(500) COMMENT '描述',
    skill_content TEXT COMMENT 'SKILL.md内容',
    source_type VARCHAR(20) DEFAULT 'database' COMMENT '源类型',
    source_path VARCHAR(500) COMMENT '源路径',
    is_builtin BOOLEAN DEFAULT FALSE COMMENT '是否内置',
    version VARCHAR(50) COMMENT '版本',
    status VARCHAR(20) DEFAULT 'active' COMMENT '状态',
    created_by VARCHAR(100) COMMENT '创建者',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_skill_name (skill_name),
    INDEX idx_is_builtin (is_builtin)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill配置表';

-- Skill资源表
CREATE TABLE IF NOT EXISTS agent_skill_resource (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    skill_id BIGINT NOT NULL COMMENT 'Skill ID',
    resource_path VARCHAR(500) NOT NULL COMMENT '资源路径',
    resource_content LONGTEXT COMMENT '资源内容',
    resource_type VARCHAR(50) COMMENT '资源类型',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (skill_id) REFERENCES agent_skill(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Skill资源表';
