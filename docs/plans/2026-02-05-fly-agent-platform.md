# Fly Agent企业级智能体平台实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**目标:** 基于AgentScope Java构建企业级智能体平台，支持Skill/Tool管理、任务调度、可观测性等企业特性

**架构:** Maven多模块分层架构，采用dao/service/common经典结构，集成AgentScope Java、智谱AI、MySQL、Redis、XXL-Job等组件

**技术栈:** Spring Boot 3.2.0, AgentScope Java 0.0.2, MyBatis, Redis, MySQL 8.0, XXL-Job 2.4.0, JDK 17

---

## 前置条件

- JDK 17+ 已安装并配置JAVA_HOME
- Maven 3.8+ 已安装
- MySQL 8.0+ 已运行
- Redis 6.0+ 已运行
- XXL-Job Admin已部署（可选，任务调度使用）

---

## 阶段一：项目脚手架搭建

### Task 1: 创建Maven父POM

**Files:**
- Create: `pom.xml` (根目录父POM)

**Step 1: 创建父POM文件**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.fly.agent</groupId>
    <artifactId>fly-agent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <name>Fly Agent Parent</name>
    <description>Enterprise-grade AI Agent Platform based on AgentScope Java</description>

    <modules>
        <module>fly-agent-common</module>
        <module>fly-agent-dao</module>
        <module>fly-agent-service</module>
        <module>fly-agent-task</module>
        <module>fly-agent-mcp</module>
        <module>fly-agent-api</module>
        <module>fly-agent-server</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <!-- Spring Boot -->
        <spring-boot.version>3.2.0</spring-boot.version>

        <!-- AgentScope Java -->
        <agentscope.version>0.0.2</agentscope.version>

        <!-- Database -->
        <mysql.version>8.0.33</mysql.version>
        <mybatis-plus.version>3.5.5</mybatis-plus.version>
        <druid.version>1.2.20</druid.version>

        <!-- Redis -->
        <jedis.version>5.1.0</jedis.version>

        <!-- XXL-Job -->
        <xxl-job.version>2.4.0</xxl-job.version>

        <!-- Utils -->
        <lombok.version>1.18.30</lombok.version>
        <hutool.version>5.8.24</hutool.version>
        <fastjson2.version>2.0.44</fastjson2.version>

        <!-- Test -->
        <junit.version>5.10.1</junit.version>
        <mockito.version>5.8.0</mockito.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Spring Boot -->
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- AgentScope Java -->
            <dependency>
                <groupId>com.alibaba</groupId>
                <artifactId>agentscope-java</artifactId>
                <version>${agentscope.version}</version>
            </dependency>

            <!-- MyBatis Plus -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>

            <!-- MySQL Driver -->
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <version>${mysql.version}</version>
            </dependency>

            <!-- Druid -->
            <dependency>
                <groupId>com.alibaba</groupId>
                <artifactId>druid-spring-boot-3-starter</artifactId>
                <version>${druid.version}</version>
            </dependency>

            <!-- Redis -->
            <dependency>
                <groupId>redis.clients</groupId>
                <artifactId>jedis</artifactId>
                <version>${jedis.version}</version>
            </dependency>

            <!-- XXL-Job -->
            <dependency>
                <groupId>com.xuxueli</groupId>
                <artifactId>xxl-job-core</artifactId>
                <version>${xxl-job.version}</version>
            </dependency>

            <!-- Lombok -->
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
            </dependency>

            <!-- Hutool -->
            <dependency>
                <groupId>cn.hutool</groupId>
                <artifactId>hutool-all</artifactId>
                <version>${hutool.version}</version>
            </dependency>

            <!-- FastJson2 -->
            <dependency>
                <groupId>com.alibaba.fastjson2</groupId>
                <artifactId>fastjson2</artifactId>
                <version>${fastjson2.version}</version>
            </dependency>

            <!-- Internal Modules -->
            <dependency>
                <groupId>com.fly.agent</groupId>
                <artifactId>fly-agent-common</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fly.agent</groupId>
                <artifactId>fly-agent-dao</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fly.agent</groupId>
                <artifactId>fly-agent-service</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fly.agent</groupId>
                <artifactId>fly-agent-task</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fly.agent</groupId>
                <artifactId>fly-agent-mcp</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.fly.agent</groupId>
                <artifactId>fly-agent-api</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.11.0</version>
                    <configuration>
                        <source>${java.version}</source>
                        <target>${java.version}</target>
                        <encoding>${project.build.sourceEncoding}</encoding>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

**Step 2: 验证POM结构**

Run: `mvn validate`
Expected: BUILD SUCCESS

**Step 3: 提交父POM**

```bash
git add pom.xml
git commit -m "feat: add Maven parent POM with dependency management"
```

---

### Task 2: 创建Common模块

**Files:**
- Create: `fly-agent-common/pom.xml`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/constants/AgentConstants.java`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/enums/AgentStatus.java`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/enums/ConversationRole.java`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/exception/BusinessException.java`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/exception/GlobalExceptionHandler.java`
- Create: `fly-agent-common/src/main/java/com/fly/agent/common/dto/Result.java`

**Step 1: 创建Common模块POM**

```bash
mkdir -p fly-agent-common/src/main/java/com/fly/agent/common
mkdir -p fly-agent-common/src/main/resources
```

Create: `fly-agent-common/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fly.agent</groupId>
        <artifactId>fly-agent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>fly-agent-common</artifactId>
    <packaging>jar</packaging>

    <name>Fly Agent Common</name>
    <description>Common components and utilities</description>

    <dependencies>
        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>

        <!-- Hutool -->
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
        </dependency>

        <!-- FastJson2 -->
        <dependency>
            <groupId>com.alibaba.fastjson2</groupId>
            <artifactId>fastjson2</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Step 2: 创建常量类**

Create: `fly-agent-common/src/main/java/com/fly/agent/common/constants/AgentConstants.java`

```java
package com.fly.agent.common.constants;

public class AgentConstants {
    public static final String DEFAULT_AGENT_NAME = "default";
    public static final String DEFAULT_MODEL = "glm-4-plus";
    public static final int DEFAULT_MAX_TOKENS = 2000;
    public static final double DEFAULT_TEMPERATURE = 0.7;

    public static final String CACHE_KEY_AGENT_CONFIG = "agent:config:%s";
    public static final String CACHE_KEY_CONVERSATION_CONTEXT = "conversation:context:%s";
    public static final String CACHE_KEY_RATE_LIMIT = "ratelimit:%s:%s";
    public static final String CACHE_KEY_TOOL_RESULT = "tool:result:%s";

    public static final long CACHE_TTL_CONVERSATION = 7200; // 2 hours
    public static final long CACHE_TTL_RATE_LIMIT = 60; // 1 minute
    public static final long CACHE_TTL_TOOL_RESULT = 86400; // 24 hours

    public static final int MAX_RETRIES = 3;
    public static final long RETRY_DELAY_MS = 1000;
}
```

**Step 3: 创建枚举类**

Create: `fly-agent-common/src/main/java/com/fly/agent/common/enums/AgentStatus.java`

```java
package com.fly.agent.common.enums;

import lombok.Getter;

@Getter
public enum AgentStatus {
    CREATED("CREATED", "已创建"),
    STARTING("STARTING", "启动中"),
    RUNNING("RUNNING", "运行中"),
    STOPPING("STOPPING", "停止中"),
    STOPPED("STOPPED", "已停止"),
    ERROR("ERROR", "错误");

    private final String code;
    private final String description;

    AgentStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
```

Create: `fly-agent-common/src/main/java/com/fly/agent/common/enums/ConversationRole.java`

```java
package com.fly.agent.common.enums;

import lombok.Getter;

@Getter
public enum ConversationRole {
    USER("user", "用户"),
    ASSISTANT("assistant", "助手"),
    SYSTEM("system", "系统"),
    TOOL("tool", "工具");

    private final String code;
    private final String description;

    ConversationRole(String code, String description) {
        this.code = code;
        this.description = description;
    }
}
```

**Step 4: 创建异常类**

Create: `fly-agent-common/src/main/java/com/fly/agent/common/exception/BusinessException.java`

```java
package com.fly.agent.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final String code;

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this("BUSINESS_ERROR", message);
    }
}
```

Create: `fly-agent-common/src/main/java/com/fly/agent/common/exception/GlobalExceptionHandler.java`

```java
package com.fly.agent.common.exception;

import com.fly.agent.common.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.error("Business exception: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("System exception", e);
        return Result.error("SYSTEM_ERROR", "系统异常: " + e.getMessage());
    }
}
```

**Step 5: 创建统一响应DTO**

Create: `fly-agent-common/src/main/java/com/fly/agent/common/dto/Result.java`

```java
package com.fly.agent.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {
    private String code;
    private String message;
    private T data;
    private Long timestamp;

    public static <T> Result<T> ok(T data) {
        return new Result<>("SUCCESS", "操作成功", data, System.currentTimeMillis());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> error(String code, String message) {
        return new Result<>(code, message, null, System.currentTimeMillis());
    }

    public static <T> Result<T> error(String message) {
        return error("ERROR", message);
    }
}
```

**Step 6: 编译验证**

Run: `cd fly-agent-common && mvn clean compile`
Expected: BUILD SUCCESS

**Step 7: 提交Common模块**

```bash
git add fly-agent-common/
git commit -m "feat: add common module with constants, enums, exceptions, and DTO"
```

---

### Task 3: 创建DAO模块

**Files:**
- Create: `fly-agent-dao/pom.xml`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/AgentMapper.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/ConversationMapper.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/MessageMapper.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/SkillMapper.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/AgentEntity.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/ConversationEntity.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/MessageEntity.java`
- Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/SkillEntity.java`
- Create: `fly-agent-dao/src/main/resources/mapper/AgentMapper.xml`
- Create: `fly-agent-dao/src/main/resources/db/migration/V1__init_schema.sql`

**Step 1: 创建DAO模块POM**

```bash
mkdir -p fly-agent-dao/src/main/java/com/fly/agent/dao
mkdir -p fly-agent-dao/src/main/resources/mapper
mkdir -p fly-agent-dao/src/main/resources/db/migration
```

Create: `fly-agent-dao/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fly.agent</groupId>
        <artifactId>fly-agent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>fly-agent-dao</artifactId>
    <packaging>jar</packaging>

    <name>Fly Agent DAO</name>
    <description>Data access layer</description>

    <dependencies>
        <!-- Common Module -->
        <dependency>
            <groupId>com.fly.agent</groupId>
            <artifactId>fly-agent-common</artifactId>
        </dependency>

        <!-- MyBatis Plus -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>

        <!-- MySQL Driver -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>

        <!-- Druid -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>druid-spring-boot-3-starter</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Step 2: 创建数据库初始化脚本**

Create: `fly-agent-dao/src/main/resources/db/migration/V1__init_schema.sql`

```sql
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
```

**Step 3: 创建实体类**

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/AgentEntity.java`

```java
package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_config")
public class AgentEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String agentName;
    private String agentType;
    private String systemPrompt;
    private String llmConfig;
    private String tools;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/ConversationEntity.java`

```java
package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class ConversationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;
    private Long agentId;
    private String userId;
    private String title;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/MessageEntity.java`

```java
package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("message")
public class MessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;
    private String role;
    private String content;
    private String toolCalls;
    private Integer tokens;
    private LocalDateTime timestamp;
}
```

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/entity/SkillEntity.java`

```java
package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("agent_skill")
public class SkillEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillName;
    private String description;
    private String skillContent;
    private String sourceType;
    private String sourcePath;
    private Boolean isBuiltin;
    private String version;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

**Step 4: 创建Mapper接口**

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/AgentMapper.java`

```java
package com.fly.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.AgentEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}
```

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/ConversationMapper.java`

```java
package com.fly.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
}
```

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/MessageMapper.java`

```java
package com.fly.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
```

Create: `fly-agent-dao/src/main/java/com/fly/agent/dao/mapper/SkillMapper.java`

```java
package com.fly.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fly.agent.dao.entity.SkillEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkillMapper extends BaseMapper<SkillEntity> {
}
```

**Step 5: 创建MyBatis配置**

Create: `fly-agent-dao/src/main/resources/mapper/AgentMapper.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.fly.agent.dao.mapper.AgentMapper">
</mapper>
```

**Step 6: 编译验证**

Run: `cd fly-agent-dao && mvn clean compile`
Expected: BUILD SUCCESS

**Step 7: 提交DAO模块**

```bash
git add fly-agent-dao/
git commit -m "feat: add DAO module with entities, mappers, and database schema"
```

---

### Task 4: 创建Service模块

**Files:**
- Create: `fly-agent-service/pom.xml`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/agent/AgentService.java`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/agent/AgentRegistry.java`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/conversation/ConversationService.java`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/llm/ZhipuAIClient.java`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/llm/LLMService.java`
- Create: `fly-agent-service/src/main/java/com/fly/agent/service/skills/SkillManagementService.java`

**Step 1: 创建Service模块POM**

```bash
mkdir -p fly-agent-service/src/main/java/com/fly/agent/service
mkdir -p fly-agent-service/src/main/resources
```

Create: `fly-agent-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fly.agent</groupId>
        <artifactId>fly-agent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>fly-agent-service</artifactId>
    <packaging>jar</packaging>

    <name>Fly Agent Service</name>
    <description>Business service layer</description>

    <dependencies>
        <!-- DAO Module -->
        <dependency>
            <groupId>com.fly.agent</groupId>
            <artifactId>fly-agent-dao</artifactId>
        </dependency>

        <!-- AgentScope Java -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>agentscope-java</artifactId>
        </dependency>

        <!-- Spring Boot -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- HTTP Client -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webflux</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Step 2: 创建智谱AI客户端**

Create: `fly-agent-service/src/main/java/com/fly/agent/service/llm/ZhipuAIClient.java`

```java
package com.fly.agent.service.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ZhipuAIClient {

    private final WebClient webClient;
    private final String apiKey;

    private static final String CHAT_COMPLETION_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";

    public ZhipuAIClient(@Value("${agent.zhipu.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.webClient = WebClient.builder()
                .baseUrl(CHAT_COMPLETION_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .build();
    }

    public Mono<JSONObject> chatCompletion(String model, List<Map<String, String>> messages,
                                          Map<String, Object> extraConfig) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("messages", messages);

        if (extraConfig != null) {
            extraConfig.forEach(requestBody::put);
        }

        log.info("Calling ZhipuAI chat completion, model: {}", model);

        return webClient.post()
                .bodyValue(requestBody.toJSONString())
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(30))
                .map(this::parseResponse)
                .doOnError(e -> log.error("ZhipuAI call failed", e));
    }

    private JSONObject parseResponse(String responseBody) {
        return JSON.parseObject(responseBody);
    }
}
```

**Step 3: 创建LLM服务**

Create: `fly-agent-service/src/main/java/com/fly/agent/service/llm/LLMService.java`

```java
package com.fly.agent.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class LLMService {

    private final ZhipuAIClient zhipuAIClient;

    public Mono<String> chat(String model, List<Map<String, String>> messages) {
        return chat(model, messages, null);
    }

    public Mono<String> chat(String model, List<Map<String, String>> messages,
                            Map<String, Object> extraConfig) {
        return zhipuAIClient.chatCompletion(model, messages, extraConfig)
                .map(response -> {
                    String content = response.getJSONObject("choices")
                            .getJSONArray(0)
                            .getJSONObject("message")
                            .getString("content");
                    log.info("LLM response received, length: {}", content.length());
                    return content;
                });
    }
}
```

**Step 4: 创建Agent服务**

Create: `fly-agent-service/src/main/java/com/fly/agent/service/agent/AgentService.java`

```java
package com.fly.agent.service.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.common.enums.AgentStatus;
import com.fly.agent.dao.entity.AgentEntity;
import com.fly.agent.dao.mapper.AgentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentMapper agentMapper;
    private final AgentRegistry agentRegistry;
    private final RedisTemplate<String, Object> redisTemplate;

    public AgentEntity createAgent(AgentEntity agent) {
        agent.setStatus(AgentStatus.CREATED.getCode());
        agent.setCreatedAt(LocalDateTime.now());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.insert(agent);
        log.info("Agent created: {}", agent.getAgentName());
        return agent;
    }

    public AgentEntity getAgent(Long id) {
        return agentMapper.selectById(id);
    }

    public AgentEntity getAgentByName(String agentName) {
        LambdaQueryWrapper<AgentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentEntity::getAgentName, agentName);
        return agentMapper.selectOne(wrapper);
    }

    public List<AgentEntity> listAgents() {
        return agentMapper.selectList(null);
    }

    public void startAgent(Long id) {
        AgentEntity agent = getAgent(id);
        if (agent == null) {
            throw new RuntimeException("Agent not found");
        }

        agent.setStatus(AgentStatus.RUNNING.getCode());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);

        // 注册到AgentRegistry
        agentRegistry.registerAgent(agent);

        // 缓存配置
        String cacheKey = String.format("agent:config:%d", id);
        redisTemplate.opsForValue().set(cacheKey, agent);

        log.info("Agent started: {}", agent.getAgentName());
    }

    public void stopAgent(Long id) {
        AgentEntity agent = getAgent(id);
        if (agent == null) {
            throw new RuntimeException("Agent not found");
        }

        agent.setStatus(AgentStatus.STOPPED.getCode());
        agent.setUpdatedAt(LocalDateTime.now());
        agentMapper.updateById(agent);

        // 从Registry移除
        agentRegistry.unregisterAgent(id);

        log.info("Agent stopped: {}", agent.getAgentName());
    }
}
```

**Step 5: 创建Agent注册中心**

Create: `fly-agent-service/src/main/java/com/fly/agent/service/agent/AgentRegistry.java`

```java
package com.fly.agent.service.agent;

import com.fly.agent.dao.entity.AgentEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AgentRegistry {

    private final ConcurrentHashMap<Long, AgentEntity> registry = new ConcurrentHashMap<>();

    public void registerAgent(AgentEntity agent) {
        registry.put(agent.getId(), agent);
        log.info("Agent registered: {}", agent.getAgentName());
    }

    public void unregisterAgent(Long agentId) {
        registry.remove(agentId);
        log.info("Agent unregistered: {}", agentId);
    }

    public AgentEntity getAgent(Long agentId) {
        return registry.get(agentId);
    }

    public boolean containsAgent(Long agentId) {
        return registry.containsKey(agentId);
    }
}
```

**Step 6: 创建对话服务**

Create: `fly-agent-service/src/main/java/com/fly/agent/service/conversation/ConversationService.java`

```java
package com.fly.agent.service.conversation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fly.agent.dao.entity.ConversationEntity;
import com.fly.agent.dao.entity.MessageEntity;
import com.fly.agent.dao.mapper.ConversationMapper;
import com.fly.agent.dao.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final LLMService llmService;
    private final RedisTemplate<String, Object> redisTemplate;

    public ConversationEntity createConversation(Long agentId, String userId) {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setSessionId(UUID.randomUUID().toString());
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        conversationMapper.insert(conversation);
        log.info("Conversation created: {}", conversation.getSessionId());

        return conversation;
    }

    public Mono<String> chat(String sessionId, String userMessage) {
        // 保存用户消息
        saveMessage(sessionId, "user", userMessage);

        // 获取对话历史
        List<MessageEntity> history = getConversationHistory(sessionId);

        // 调用LLM
        return llmService.chat("glm-4-plus", convertToMessages(history))
                .map(response -> {
                    // 保存助手响应
                    saveMessage(sessionId, "assistant", response);
                    return response;
                });
    }

    private void saveMessage(String sessionId, String role, String content) {
        LambdaQueryWrapper<ConversationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConversationEntity::getSessionId, sessionId);
        ConversationEntity conversation = conversationMapper.selectOne(wrapper);

        MessageEntity message = new MessageEntity();
        message.setConversationId(conversation.getId());
        message.setRole(role);
        message.setContent(content);
        message.setTimestamp(LocalDateTime.now());

        messageMapper.insert(message);
    }

    private List<MessageEntity> getConversationHistory(String sessionId) {
        // TODO: 实现获取对话历史
        return List.of();
    }

    private List<java.util.Map<String, String>> convertToMessages(List<MessageEntity> history) {
        // TODO: 转换为LLM消息格式
        return List.of();
    }
}
```

**Step 7: 编译验证**

Run: `cd fly-agent-service && mvn clean compile`
Expected: BUILD SUCCESS

**Step 8: 提交Service模块**

```bash
git add fly-agent-service/
git commit -m "feat: add service module with agent, conversation, and LLM services"
```

---

### Task 5: 创建API模块

**Files:**
- Create: `fly-agent-api/pom.xml`
- Create: `fly-agent-api/src/main/java/com/fly/agent/api/controller/AgentController.java`
- Create: `fly-agent-api/src/main/java/com/fly/agent/api/controller/ChatController.java`
- Create: `fly-agent-api/src/main/java/com/fly/agent/api/config/WebConfig.java`

**Step 1: 创建API模块POM**

```bash
mkdir -p fly-agent-api/src/main/java/com/fly/agent/api
```

Create: `fly-agent-api/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fly.agent</groupId>
        <artifactId>fly-agent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>fly-agent-api</artifactId>
    <packaging>jar</packaging>

    <name>Fly Agent API</name>
    <description>REST API layer</description>

    <dependencies>
        <!-- Service Module -->
        <dependency>
            <groupId>com.fly.agent</groupId>
            <artifactId>fly-agent-service</artifactId>
        </dependency>

        <!-- Spring Boot Web -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- Validation -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Lombok -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
    </dependencies>
</project>
```

**Step 2: 创建Agent控制器**

Create: `fly-agent-api/src/main/java/com/fly/agent/api/controller/AgentController.java`

```java
package com.fly.agent.api.controller;

import com.fly.agent.common.dto.Result;
import com.fly.agent.dao.entity.AgentEntity;
import com.fly.agent.service.agent.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;

    @PostMapping
    public Result<AgentEntity> createAgent(@RequestBody AgentEntity agent) {
        return Result.ok(agentService.createAgent(agent));
    }

    @GetMapping("/{id}")
    public Result<AgentEntity> getAgent(@PathVariable Long id) {
        return Result.ok(agentService.getAgent(id));
    }

    @GetMapping
    public Result<List<AgentEntity>> listAgents() {
        return Result.ok(agentService.listAgents());
    }

    @PostMapping("/{id}/start")
    public Result<Void> startAgent(@PathVariable Long id) {
        agentService.startAgent(id);
        return Result.ok();
    }

    @PostMapping("/{id}/stop")
    public Result<Void> stopAgent(@PathVariable Long id) {
        agentService.stopAgent(id);
        return Result.ok();
    }
}
```

**Step 3: 创建Chat控制器**

Create: `fly-agent-api/src/main/java/com/fly/agent/api/controller/ChatController.java`

```java
package com.fly.agent.api.controller;

import com.fly.agent.common.dto.Result;
import com.fly.agent.dao.entity.ConversationEntity;
import com.fly.agent.service.conversation.ConversationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ConversationService conversationService;

    @PostMapping("/conversations")
    public Result<ConversationEntity> createConversation(
            @RequestParam Long agentId,
            @RequestParam(required = false) String userId) {
        return Result.ok(conversationService.createConversation(agentId, userId));
    }

    @PostMapping("/completions")
    public Mono<Result<String>> chat(@RequestParam String sessionId,
                                     @RequestBody String message) {
        return conversationService.chat(sessionId, message)
                .map(Result::ok);
    }
}
```

**Step 4: 创建Web配置**

Create: `fly-agent-api/src/main/java/com/fly/agent/api/config/WebConfig.java`

```java
package com.fly.agent.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
```

**Step 5: 编译验证**

Run: `cd fly-agent-api && mvn clean compile`
Expected: BUILD SUCCESS

**Step 6: 提交API模块**

```bash
git add fly-agent-api/
git commit -m "feat: add API module with REST controllers"
```

---

### Task 6: 创建Server模块（启动模块）

**Files:**
- Create: `fly-agent-server/pom.xml`
- Create: `fly-agent-server/src/main/java/com/fly/agent/AgentApplication.java`
- Create: `fly-agent-server/src/main/resources/application.yml`
- Create: `fly-agent-server/src/main/resources/application-dev.yml`
- Create: `fly-agent-server/src/main/resources/logback-spring.xml`

**Step 1: 创建Server模块POM**

```bash
mkdir -p fly-agent-server/src/main/java/com/fly/agent
mkdir -p fly-agent-server/src/main/resources
```

Create: `fly-agent-server/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.fly.agent</groupId>
        <artifactId>fly-agent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>fly-agent-server</artifactId>
    <packaging>jar</packaging>

    <name>Fly Agent Server</name>
    <description>Application starter</description>

    <dependencies>
        <!-- API Module -->
        <dependency>
            <groupId>com.fly.agent</groupId>
            <artifactId>fly-agent-api</artifactId>
        </dependency>

        <!-- Spring Boot Starter -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- MyBatis -->
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>

        <!-- Redis -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>

        <!-- Test -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

**Step 2: 创建主启动类**

Create: `fly-agent-server/src/main/java/com/fly/agent/AgentApplication.java`

```java
package com.fly.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.fly.agent")
@MapperScan("com.fly.agent.dao.mapper")
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
```

**Step 3: 创建主配置文件**

Create: `fly-agent-server/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: fly-agent
  profiles:
    active: dev

  datasource:
    type: com.alibaba.druid.pool.DruidDataSource
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://localhost:3306/fly_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root

  redis:
    host: localhost
    port: 6379
    database: 0
    timeout: 3000

agent:
  zhipu:
    api-key: 5ae82851c361407cb40756bdaa6397ad.pqsvPmBEMop791h0
    model: glm-4-plus
    temperature: 0.7
    max-tokens: 2000

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  mapper-locations: classpath:mapper/*.xml

logging:
  level:
    com.fly.agent: DEBUG
    com.alibaba.druid: INFO
```

**Step 4: 创建开发环境配置**

Create: `fly-agent-server/src/main/resources/application-dev.yml`

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/fly_agent?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai
    username: root
    password: root

logging:
  level:
    root: INFO
    com.fly.agent: DEBUG
```

**Step 5: 创建日志配置**

Create: `fly-agent-server/src/main/resources/logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="appName" source="spring.application.name"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/${appName}.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/${appName}-%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>30</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>

    <logger name="com.fly.agent" level="DEBUG"/>
</configuration>
```

**Step 6: 编译打包验证**

Run: `cd fly-agent-server && mvn clean package`
Expected: BUILD SUCCESS and generates JAR file

**Step 7: 提交Server模块**

```bash
git add fly-agent-server/
git commit -m "feat: add server module with Spring Boot application"
```

---

### Task 7: 创建Skill文件系统目录结构

**Files:**
- Create: `skills/builtin/data-analysis/SKILL.md`
- Create: `skills/builtin/code-review/SKILL.md`
- Create: `skills/builtin/document-generation/SKILL.md`

**Step 1: 创建Skills目录结构**

```bash
mkdir -p skills/builtin/data-analysis/references
mkdir -p skills/builtin/data-analysis/examples
mkdir -p skills/builtin/code-review/references
mkdir -p skills/builtin/document-generation/references
mkdir -p skills/custom
```

**Step 2: 创建数据分析Skill**

Create: `skills/builtin/data-analysis/SKILL.md`

```markdown
---
name: data_analysis
description: Use this skill when analyzing data, calculating statistics, generating reports, or performing data visualization
version: 1.0.0
author: fly-agent-team
---

# Data Analysis Skill

## Feature Overview
This skill provides comprehensive data analysis capabilities including:
- Statistical calculations (mean, median, standard deviation, etc.)
- Data visualization (charts, graphs, plots)
- Report generation in multiple formats
- Data cleaning and transformation

## Usage Instructions
1. Use this skill when the user asks to analyze datasets
2. Load data using available data loading tools
3. Apply appropriate statistical methods
4. Generate visualizations and reports
5. Present findings in a clear, actionable format

## Available Resources
- `references/statistical-formulas.md`: Common statistical formulas reference
- `references/best-practices.md`: Data analysis best practices
- `examples/sales-analysis.java`: Complete sales data analysis example

## Prerequisites
- Data must be in CSV, Excel, or JSON format
- Maximum recommended dataset size: 1GB
- Requires numerical or categorical data types
```

**Step 3: 创建代码审查Skill**

Create: `skills/builtin/code-review/SKILL.md`

```markdown
---
name: code_review
description: Use this skill when reviewing code, identifying bugs, suggesting improvements, or checking best practices
version: 1.0.0
author: fly-agent-team
---

# Code Review Skill

## Feature Overview
This skill provides comprehensive code review capabilities including:
- Bug detection and security vulnerability identification
- Code quality and style analysis
- Performance optimization suggestions
- Best practices and design pattern recommendations

## Usage Instructions
1. Use this skill when the user requests code review
2. Analyze code structure and logic
3. Identify potential issues and improvements
4. Provide specific, actionable recommendations
5. Explain reasoning for each suggestion

## Available Resources
- `references/coding-standards.md`: Coding standards and conventions
- `references/security-checklist.md`: Security vulnerability checklist
- `examples/before-after.java`: Code improvement examples

## Supported Languages
- Java
- Python
- JavaScript
- Go
```

**Step 4: 创建文档生成Skill**

Create: `skills/builtin/document-generation/SKILL.md`

```markdown
---
name: document_generation
description: Use this skill when generating documentation, creating reports, writing technical content, or producing structured documents
version: 1.0.0
author: fly-agent-team
---

# Document Generation Skill

## Feature Overview
This skill provides comprehensive document generation capabilities including:
- Technical documentation (API docs, user guides)
- Business reports and summaries
- Code documentation and comments
- Structured content creation

## Usage Instructions
1. Use this skill when the user requests document generation
2. Understand the target audience and purpose
3. Organize content with clear structure
4. Use appropriate formatting and style
5. Include relevant examples and references

## Available Resources
- `references/doc-templates.md`: Document templates
- `references/style-guide.md`: Writing style guidelines
- `examples/api-doc.md`: API documentation example

## Output Formats
- Markdown
- HTML
- Plain text
- JSON (structured data)
```

**Step 5: 提交Skills文件系统**

```bash
git add skills/
git commit -m "feat: add builtin skills with markdown definitions"
```

---

### Task 8: 更新README文档

**Files:**
- Modify: `README.md`

**Step 1: 更新README**

```bash
cat > README.md << 'EOF'
# Fly Agent 企业级智能体平台

基于 AgentScope Java 构建的企业级 AI 智能体平台，支持 Skill/Tool 管理、任务调度、可观测性等企业特性。

## 项目架构

### 模块结构

```
fly-agent/
├── fly-agent/        # 父POM（版本管理）
├── fly-agent-common/        # 公共组件层
├── fly-agent-dao/          # 数据访问层
├── fly-agent-service/      # 业务服务层
│   ├── agent/              # Agent管理
│   ├── conversation/       # 对话服务
│   ├── llm/                # LLM服务（智谱AI）
│   ├── skills/             # Skill管理
│   └── tools/              # Tool管理
├── fly-agent-task/         # 任务调度（XXL-Job）
├── fly-agent-mcp/          # MCP协议支持
├── fly-agent-api/          # REST API层
├── fly-agent-server/       # 启动模块
└── skills/                 # Skill文件系统
    ├── builtin/            # 内置Skills
    └── custom/             # 自定义Skills
```

### 技术栈

- **JDK**: 17+
- **Spring Boot**: 3.2.0
- **AgentScope Java**: 0.0.2
- **数据库**: MySQL 8.0
- **缓存**: Redis 6.0
- **任务调度**: XXL-Job 2.4.0
- **LLM**: 智谱AI GLM-4-Plus

## 快速开始

### 前置条件

1. JDK 17+ 已安装
2. Maven 3.8+ 已安装
3. MySQL 8.0+ 已运行
4. Redis 6.0+ 已运行

### 数据库初始化

```bash
# 创建数据库
mysql -u root -p -e "CREATE DATABASE fly_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 初始化表结构（Flyway会自动执行）
```

### 配置修改

编辑 `fly-agent-server/src/main/resources/application-dev.yml`:

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/fly_agent
    username: your_username
    password: your_password
  redis:
    host: localhost
    port: 6379

agent:
  zhipu:
    api-key: your_zhipu_api_key
```

### 编译运行

```bash
# 编译项目
mvn clean package

# 运行服务
cd fly-agent-server
java -jar target/fly-agent-server-1.0.0-SNAPSHOT.jar

# 或使用Maven插件运行
mvn spring-boot:run -pl fly-agent-server
```

### 访问服务

服务启动后，访问: http://localhost:8080

## 核心特性

### 1. Agent管理

- 创建、启动、停止Agent
- Agent配置管理
- Agent状态监控

### 2. Skill系统

基于AgentScope Java的Skill机制：

- **渐进式披露**: 按需加载Skill内容，优化上下文
- **双模存储**: 内置Skills（文件系统）+ 自定义Skills（数据库）
- **Tool绑定**: Skill可以绑定多个Tool
- **版本管理**: 支持Skill版本控制和灰度发布

### 3. 对话服务

- 多轮对话支持
- 会话上下文管理
- 消息历史记录

### 4. LLM集成

- 智谱AI GLM-4-Plus集成
- 统一的LLM调用接口
- 可扩展到其他LLM提供商

## API文档

### Agent管理

- `POST /api/v1/agents` - 创建Agent
- `GET /api/v1/agents/{id}` - 查询Agent
- `GET /api/v1/agents` - Agent列表
- `POST /api/v1/agents/{id}/start` - 启动Agent
- `POST /api/v1/agents/{id}/stop` - 停止Agent

### 对话接口

- `POST /api/v1/chat/conversations` - 创建对话
- `POST /api/v1/chat/completions` - 发送消息

## 开发指南

### 添加自定义Skill

1. 在 `skills/custom/` 创建新目录
2. 编写 `SKILL.md` 文件
3. 添加资源文件（可选）
4. 重启服务或调用刷新API

### 添加自定义Tool

1. 在 `fly-agent-service` 创建Tool类
2. 实现 `AgentTool` 接口
3. 注册到 `ToolRegistry`

## 许可证

Apache License 2.0

## 联系方式

- 项目地址: [GitHub](https://github.com/your-org/fly-agent)
- 文档: [Docs](https://your-docs-site.com)
EOF
```

**Step 2: 提交README**

```bash
git add README.md
git commit -m "docs: update README with project overview and quick start guide"
```

---

### Task 9: 最终构建验证

**Step 1: 完整构建**

Run: `mvn clean package -DskipTests`
Expected: All modules build successfully, generates JAR in fly-agent-server/target/

**Step 2: 验证JAR文件**

Run: `ls -lh fly-agent-server/target/*.jar`
Expected: Shows fly-agent-server-1.0.0-SNAPSHOT.jar (should be 50-100MB)

**Step 3: 提交最终代码**

```bash
git add .
git commit -m "feat: complete fly-agent platform MVP"
```

---

## 后续优化方向

### 阶段二：核心功能完善

1. **Skill管理完善**
   - Skill CRUD API
   - Skill文件系统与数据库同步
   - Skill版本管理

2. **Tool管理**
   - Tool注册与发现
   - Tool执行监控
   - 自定义Tool开发支持

3. **可观测性**
   - 结构化日志
   - Metrics指标采集
   - 分布式追踪

### 阶段三：企业级特性

1. **XXL-Job集成**
   - 定时任务配置
   - 任务监控

2. **MCP协议支持**
   - MCP服务器实现
   - MCP客户端集成

3. **安全与权限**
   - API Key加密存储
   - 多租户隔离
   - 访问控制

### 阶段四：性能优化

1. **并发优化**
   - Agent实例池化
   - 异步Tool执行

2. **缓存优化**
   - Skill预热
   - 多级缓存策略

3. **数据库优化**
   - 索引优化
   - 读写分离

---

## 附录：开发环境检查清单

- [ ] JDK 17+ 已安装并配置 JAVA_HOME
- [ ] Maven 3.8+ 已安装
- [ ] MySQL 8.0+ 已运行，已创建数据库
- [ ] Redis 6.0+ 已运行
- [ ] 智谱AI API Key已配置
- [ ] (可选) XXL-Job Admin已部署

---

**计划完成！** 保存至: `docs/plans/2026-02-05-fly-agent-platform.md`
