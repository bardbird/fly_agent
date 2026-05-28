# Fly Agent Platform - 项目概览

## 项目简介

Fly Agent 是一个基于 **AgentScope Java** 构建的企业级 AI 智能体平台，提供完整的 Agent 管理、Skill/Tool 管理、任务调度和可观测性能力。

## 技术架构

### 核心技术栈

- **JDK**: 17
- **Spring Boot**: 3.2.0
- **AgentScope Java**: 0.0.2 (阿里巴巴开源的 Agent 框架)
- **数据库**: MySQL 8.0 + MyBatis-Plus
- **缓存**: Redis 6.0
- **任务调度**: XXL-Job 2.4.0
- **LLM**: 智谱AI GLM-5（支持 glm-4-plus, glm-4-air, glm-4-flash）

### 模块结构

```
fly-agent/
├── fly-agent-common/       # 公共组件层
│   ├── 响应对象封装
│   ├── 异常处理
│   └── 工具类
│
├── fly-agent-dao/          # 数据访问层
│   ├── 实体类 (Entity)
│   ├── Mapper 接口
│   └── MyBatis XML 配置
│
├── fly-agent-service/      # 业务服务层
│   ├── agent/             # Agent 管理服务
│   ├── conversation/      # 对话服务
│   ├── llm/               # LLM 服务（智谱AI）
│   ├── skills/            # Skill 管理
│   └── tools/             # Tool 管理
│
├── fly-agent-task/         # 任务调度模块
│   ├── config/XxlJobConfig.java    # XXL-Job 配置
│   └── job/                        # 任务示例
│       ├── SimpleDemoJob.java      # 简单任务
│       ├── ParameterDemoJob.java   # 参数化任务
│       └── BusinessDemoJob.java    # 业务场景任务
│
├── fly-agent-server/       # 服务启动模块（包含 REST API）
│   ├── AgentApplication.java      # 启动类
│   └── api/                       # REST API 层
│       ├── config/WebConfig.java
│       └── controller/            # 控制器
│           ├── AgentController.java
│           └── ChatController.java
│
└── skills/                 # Skill 文件系统
    ├── builtin/           # 内置 Skills
    └── custom/            # 自定义 Skills
```

## 服务端口

- **8080**: Fly Agent Server (REST API 服务)
- **8082**: XXL-Job Executor (任务执行器)
- **9090**: XXL-Job Admin (Web 管理界面)

## 配置文件位置

### 主配置
- `fly-agent-server/src/main/resources/application.yml`
  - 服务端口: 8080
  - 数据库配置
  - Redis 配置
  - 智谱AI API Key

### XXL-Job 配置
- `fly-agent-task/src/main/resources/application.yml`
  - 执行器端口: 8082
  - Admin 地址: http://127.0.0.1:9090/xxl-job-admin
  - 执行器名称: fly-agent-executor

## 数据库

### Fly Agent 数据库
- **名称**: fly_agent
- **字符集**: utf8mb4
- **用途**: 存储 Agent、对话、Skill 等业务数据

### XXL-Job 数据库
- **名称**: xxl_job
- **字符集**: utf8mb4
- **用途**: 存储任务调度相关数据

## 关键功能

### 1. Agent 管理
- 创建、启动、停止 Agent
- Agent 配置管理
- Agent 状态监控

### 2. Skill 系统
- **渐进式披露**: 按需加载 Skill 内容，优化 Token 使用
- **双模存储**: 内置 Skills (文件系统) + 自定义 Skills (数据库)
- **Tool 绑定**: Skill 可以绑定多个 Tool
- **版本管理**: 支持版本控制和灰度发布

### 3. 任务调度 (XXL-Job)
提供 9 个 Demo Job 示例：
- **简单任务**: demoSimpleJob, demoDataProcessJob
- **参数化任务**: demoParameterJob, demoShardingJob
- **业务任务**: demoDataCleanupJob, demoStatisticsJob, demoDataSyncJob, demoHealthCheckJob, demoNotificationJob

### 4. LLM 集成
- 智谱AI GLM-5（支持 glm-4-plus, glm-4-air, glm-4-flash）
- 统一的 LLM 调用接口
- 可扩展到其他 LLM 提供商

## REST API

### Agent 管理
- `POST /api/v1/agents` - 创建 Agent
- `GET /api/v1/agents/{id}` - 查询 Agent
- `GET /api/v1/agents` - Agent 列表
- `POST /api/v1/agents/{id}/start` - 启动 Agent
- `POST /api/v1/agents/{id}/stop` - 停止 Agent

### 对话接口
- `POST /api/v1/chat/conversations` - 创建对话
- `POST /api/v1/chat/completions` - 发送消息

## 开发指南

### 编译项目
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn clean package -DskipTests
```

### 启动服务
```bash
cd fly-agent-server
mvn spring-boot:run
```

### 访问服务
- REST API: http://localhost:8080
- XXL-Job Admin: http://localhost:9090/xxl-job-admin (admin/123456)

### 添加新的 XXL-Job 任务
1. 在 `fly-agent-task/src/main/java/com/fly/agent/task/job/` 创建新类
2. 添加 `@Component` 和 `@Slf4j` 注解
3. 使用 `@XxlJob("jobHandlerName")` 注解标记方法
4. 在 XXL-Job Admin 中配置任务

示例：
```java
@Slf4j
@Component
public class MyJob {
    @XxlJob("myJobHandler")
    public void execute() {
        log.info("执行任务");
        XxlJobHelper.handleSuccess("成功");
    }
}
```

## 项目特点

1. **企业级架构**: 分层清晰，模块化设计
2. **任务调度**: 集成 XXL-Job，提供分布式任务调度能力
3. **可观测性**: 完整的日志和监控
4. **扩展性**: 支持 Skill/Tool 扩展，支持多种 LLM
5. **易用性**: 提供丰富的 Demo 和配置示例

## 相关文档

- [XXL-Job 配置指南](docs/XXL-JOB_SETUP.md)
- [Task 模块 README](fly-agent-task/README.md)
- [配置总结](CONFIGURATION_SUMMARY.md)
- [实现指南](IMPLEMENTATION_INSTRUCTIONS.md)

## 许可证

Apache License 2.0
