<div align="center">

# Fly Agent Platform

**企业级 AI 智能体平台** | 基于 AgentScope Java 构建

[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-green.svg)](https://spring.io/projects/spring-boot)
[![AgentScope](https://img.shields.io/badge/AgentScope-1.0.9-blue.svg)](https://github.com/modelscope/agentscope)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[功能特性](#功能特性) • [快速开始](#快速开始) • [技术架构](#技术架构) • [API 文档](#api-文档) • [开发指南](#开发指南)

</div>

---

## 简介

Fly Agent 是一个功能完备的企业级 AI 智能体平台，基于阿里巴巴开源的 [AgentScope Java](https://github.com/modelscope/agentscope) 框架构建。平台提供完整的 Agent 生命周期管理、Skill/Tool 扩展机制、分布式任务调度和现代化的 Web 界面。

### 核心亮点

- **渐进式披露 Skill 系统** - 按需加载，优化 Token 使用
- **双模 Skill 存储** - 内置 Skills（文件系统）+ 自定义 Skills（数据库）
- **分布式任务调度** - 基于 XXL-Job 的高可用任务调度
- **现代化 Web 界面** - React 19 + TypeScript + Tailwind CSS
- **SWE-Pro 自动采集流水线** - 从 GitHub merged PR 发现候选任务，按 SWE-bench 风格保留 issue/oracle 证据链并生成验收自检报告
- **企业级特性** - 完整的可观测性、监控和日志管理

---

## 功能特性

### Agent 管理
- **生命周期管理** - 创建、启动、停止、删除 Agent
- **配置管理** - 灵活的 Agent 配置系统
- **状态监控** - 实时监控 Agent 运行状态

### Skill 系统
- **渐进式披露** - 按需加载 Skill 内容，优化上下文窗口使用
- **双模存储** - 支持文件系统（内置）和数据库（自定义）两种存储方式
- **Tool 绑定** - Skill 可以绑定多个 Tool，实现复杂功能组合
- **版本管理** - 支持 Skill 版本控制和灰度发布

### 对话服务
- **多轮对话** - 支持连续的多轮对话交互
- **上下文管理** - 自动维护对话历史和上下文
- **流式响应** - 支持实时流式输出
- **会话持久化** - 对话记录持久化存储

### LLM 集成
- **智谱 AI** - 默认集成 GLM-5 模型（支持 glm-4-plus, glm-4-air, glm-4-flash）
- **统一接口** - 抽象的 LLM 调用接口，易于扩展
- **多模型支持** - 可扩展支持其他 LLM 提供商

### 任务调度
- **多种任务类型** - 简单任务、参数化任务、分片任务
- **业务场景示例** - 9 个开箱即用的 Demo Job
- **可视化调度** - XXL-Job Admin 提供可视化的任务管理界面
- **高可用** - 支持集群部署和故障转移

### SWE-Pro 数据采集
- **真实 issue 约束** - GitHub PR 候选必须是已合并 PR，并且标题或正文包含 `closes/fixes/resolves #issue` 等关闭关键词；没有 resolved issue 的 PR 会被跳过并计数。
- **SWE-bench 对齐字段** - 候选登记保留 `issue_url`、`issue_numbers`、`problem_statement`、`hints_text`、`test_patch_present`、`fail_to_pass`、`pass_to_pass`、`benchmark_status`、`failed_history_status`。
- **模型难度门控** - Qwen 3.6 Plus 和 Opus 4.7 使用同一 `json-edits-context` 任务 prompt 与同一 patch 物化/验证路径；Qwen 保持 thinking 开启并按 pass rate@4 过滤过易任务，Opus 按直接 pass@8 非零判定，不再通过 GPT 生成失败审查或重试提示词。
- **SWE-bench 兼容导出** - 任务包可导出 `dataset.jsonl` 与 `predictions.jsonl`：instance 保留 `repo/base_commit/problem_statement/patch/test_patch/FAIL_TO_PASS/PASS_TO_PASS`，模型预测统一记录为 `instance_id/model_name_or_path/model_patch`，用于接入官方或兼容 harness 做公平复验。
- **自检报告** - QC 阶段会生成 `乙方质检-SWE-Pro数据验收标准对照表.xlsx`，包含 `34条验收结果` 和 `汇总` 两个 sheet，对齐成功样例结构。
- **GitHub 代理** - GitHub 搜索和 PR 扫描在 `127.0.0.1:7897` 可连通时自动使用该 HTTP 代理；代理不可用时保持直连行为。

#### SWE-bench 兼容评测约束

SWE-Pro 模型评测按 SWE-bench 的公平性原则组织：同一个 task instance、同一份问题描述/测试行为证据/源码上下文、同一个候选 patch 验证器。模型可以不同，但不能让某个模型独享更多 prompt 信息、不同输出协议、静态替代判定或额外 patch 修复。

导出本地任务包为 SWE-bench-compatible JSONL：

```bash
python3 tools/swe-pro-production/scripts/export_swebench.py \
  /path/to/production-task-xxx \
  --out-dir /path/to/production-task-xxx/swebench_export
```

输出文件：

- `dataset.jsonl`：单行 instance，包含 `instance_id`、`repo`、`base_commit`、`problem_statement`、`patch`、`test_patch`、`FAIL_TO_PASS`、`PASS_TO_PASS`。
- `predictions.jsonl`：每个模型 run 一行，包含 `instance_id`、`model_name_or_path`、`model_patch`。
- `manifest.json`：导出路径和计数摘要。

---

## 技术架构

### 后端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| **JDK** | 17+ | Java 开发工具包 |
| **Spring Boot** | 3.2.0 | 应用框架 |
| **AgentScope Java** | 1.0.9 | AI 智能体框架 |
| **MySQL** | 8.0+ | 关系型数据库 |
| **Redis** | 6.0+ | 缓存和会话存储 |
| **XXL-Job** | 2.4.0 | 分布式任务调度 |
| **MyBatis-Plus** | 3.5.5 | ORM 框架 |

### 前端技术栈

| 技术 | 版本 | 说明 |
|------|------|------|
| **React** | 19.2.0 | UI 框架 |
| **TypeScript** | 5.9.3 | 类型系统 |
| **Vite** | 7.3.1 | 构建工具 |
| **Tailwind CSS** | 4.2.1 | CSS 框架 |
| **Framer Motion** | 12.34.3 | 动画库 |
| **Zustand** | 5.0.11 | 状态管理 |
| **React Query** | 5.90.21 | 数据获取 |

### 模块结构

```
fly-agent/
├── fly-agent-common/       # 公共组件层
│   ├── 响应对象封装 (Result)
│   ├── 异常处理 (Exception)
│   └── 工具类 (Utils)
│
├── fly-agent-dao/          # 数据访问层
│   ├── Entity (实体类)
│   ├── Mapper (MyBatis 接口)
│   └── XML (MyBatis 配置)
│
├── fly-agent-service/      # 业务服务层
│   ├── agent/             # Agent 管理服务
│   ├── conversation/      # 对话服务
│   ├── llm/               # LLM 服务（智谱AI）
│   ├── skills/            # Skill 管理
│   ├── swe/               # SWE-Pro 采集、候选登记、模型评估和验收报告
│   └── tools/             # Tool 管理
│
├── fly-agent-task/         # 任务调度模块
│   ├── config/            # XXL-Job 配置
│   └── job/               # 9 个 Demo Job
│
├── fly-agent-server/       # 服务启动模块（REST API）
│   ├── AgentApplication.java
│   └── api/controller/
│       ├── AgentController.java
│       └── ChatController.java
│
├── fly-agent-web/          # 前端 Web 应用
│   ├── src/
│   │   ├── components/    # React 组件
│   │   ├── hooks/         # 自定义 Hooks
│   │   ├── store/         # 状态管理
│   │   └── lib/           # 工具库
│   └── package.json
│
└── skills/                 # Skill 文件系统
    ├── builtin/           # 内置 Skills
    │   ├── code-review/
    │   ├── data-analysis/
    │   └── document-generation/
    └── custom/            # 自定义 Skills
```

---

## 快速开始

### 前置要求

确保你的开发环境已安装以下软件：

- **JDK 17+** - [下载 OpenJDK](https://openjdk.org/)
- **Maven 3.8+** - [下载 Maven](https://maven.apache.org/)
- **Node.js 18+** - [下载 Node.js](https://nodejs.org/)
- **MySQL 8.0+** - [下载 MySQL](https://dev.mysql.com/)
- **Redis 6.0+** - [下载 Redis](https://redis.io/)
- **Docker** - [下载 Docker](https://www.docker.com/) (用于运行 XXL-Job Admin)

### 数据库初始化

#### 1. 创建数据库

```bash
# 创建 Fly Agent 数据库
mysql -u root -p -e "CREATE DATABASE fly_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 创建 XXL-Job 数据库
mysql -u root -p -e "CREATE DATABASE xxl_job DEFAULT CHARACTER SET utf8mb4;"
```

#### 2. 导入 XXL-Job 表结构

```bash
# 下载 XXL-Job SQL 脚本
curl -o /tmp/xxl-job.sql https://raw.githubusercontent.com/xuxueli/xxl-job/master/doc/db/tables_xxl_job.sql

# 导入数据库
mysql -u root -p xxl_job < /tmp/xxl-job.sql
```

#### 3. 启动 XXL-Job Admin

```bash
docker run -d --name xxl-job-admin --restart=always \
  -p 9090:8080 \
  -p 9091:8081 \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai \
  --spring.datasource.username=root \
  --spring.datasource.password=YOUR_MYSQL_PASSWORD" \
  -e JAVA_OPTS="-Djavax.xml.accessExternalDTD=all" \
  xuxueli/xxl-job-admin:2.4.0
```

访问 XXL-Job Admin：http://localhost:9090/xxl-job-admin (admin/123456)

### 配置应用

编辑 `fly-agent-server/src/main/resources/application.yml`：

```yaml
server:
  port: 8080  # API 服务端口

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/fly_agent
    username: root
    password: YOUR_MYSQL_PASSWORD
  redis:
    host: 127.0.0.1
    port: 6379
    database: 0
    password: YOUR_REDIS_PASSWORD  # 可选

agent:
  zhipu:
    api-key: YOUR_ZHIPU_API_KEY  # 替换为实际的智谱AI API Key
```

### 启动服务

#### 启动后端

```bash
# 设置 JAVA_HOME（根据你的实际路径调整）
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

# 编译项目
mvn clean package -DskipTests

# 启动后端服务
cd fly-agent-server
mvn spring-boot:run

# 或使用 jar 包启动
java -jar target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

#### 启动前端

```bash
# 进入前端目录
cd fly-agent-web

# 安装依赖（首次运行）
npm install

# 启动开发服务器
npm run dev
```

### 访问应用

- **前端界面**: http://localhost:6677
- **后端 API**: http://localhost:8080
- **XXL-Job Admin**: http://localhost:9090/xxl-job-admin

---

## API 文档

### Agent 管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/agents` | 创建 Agent |
| GET | `/api/v1/agents/{id}` | 查询 Agent 详情 |
| GET | `/api/v1/agents` | 获取 Agent 列表 |
| POST | `/api/v1/agents/{id}/start` | 启动 Agent |
| POST | `/api/v1/agents/{id}/stop` | 停止 Agent |
| DELETE | `/api/v1/agents/{id}` | 删除 Agent |

### 对话接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/chat/conversations` | 创建对话会话 |
| POST | `/api/v1/chat/completions` | 发送消息并获取响应 |
| GET | `/api/v1/chat/conversations/{id}` | 获取对话详情 |
| GET | `/api/v1/chat/conversations` | 获取对话列表 |

### SWE-Pro 流水线接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/swe/github/repositories/search` | 搜索可采集 GitHub 仓库 |
| GET | `/api/v1/swe/github/pulls/merged-candidates` | 扫描 merged PR 候选；仅保留带 resolved issue 的 PR |
| GET | `/api/v1/swe/candidates` | 分页查看持久化候选 |
| POST | `/api/v1/swe/tasks` | 创建 SWE-Pro 任务 |
| POST | `/api/v1/swe/tasks/from-candidate` | 从候选 PR 创建任务 |
| GET | `/api/v1/swe/tasks` | 查询任务列表 |
| GET | `/api/v1/swe/tasks/detail` | 查询任务详情 |
| POST | `/api/v1/swe/runs/start` | 启动或续跑流水线 |
| GET | `/api/v1/swe/runs` | 查询流水线运行列表 |
| GET | `/api/v1/swe/runs/detail` | 查询流水线运行详情 |

---

## 开发指南

### 添加自定义 Skill

1. **创建 Skill 目录**

```bash
mkdir -p skills/custom/my-skill
```

2. **编写 SKILL.md 文件**

```markdown
# My Custom Skill

## 描述
这是一个自定义技能的描述。

## 使用场景
- 场景一
- 场景二

## 使用方式
在使用时，请提供以下参数：
- param1: 参数说明
- param2: 参数说明
```

3. **添加资源文件**（可选）

在 Skill 目录下添加任何需要的资源文件（如代码示例、模板等）。

4. **重启服务或调用刷新 API**

```bash
# 重启后端服务
mvn spring-boot:restart
```

### 添加自定义 Tool

1. **创建 Tool 类**

在 `fly-agent-service` 模块中创建 Tool 类：

```java
package com.fly.agent.service.tools;

import io.agentscope.core.tool.ToolDescription;
import io.agentscope.core.tool.ToolExecutionException;

@ToolDescription(name = "myTool", description = "我的自定义工具")
public class MyTool {

    public String execute(String param) throws ToolExecutionException {
        // 实现工具逻辑
        return "执行结果";
    }
}
```

2. **注册 Tool**

在 `ToolRegistry` 中注册你的 Tool：

```java
toolRegistry.register(new MyTool());
```

### 添加新的 XXL-Job 任务

1. **创建 Job 类**

在 `fly-agent-task/src/main/java/com/fly/agent/task/job/` 创建新类：

```java
@Slf4j
@Component
public class MyCustomJob {

    @XxlJob("myCustomJobHandler")
    public void execute() {
        log.info("执行自定义任务");
        String param = XxlJobHelper.getJobParam();

        try {
            // 业务逻辑
            doSomething();

            XxlJobHelper.handleSuccess("任务执行成功");
        } catch (Exception e) {
            log.error("任务执行失败", e);
            XxlJobHelper.handleFail("任务执行失败: " + e.getMessage());
        }
    }
}
```

2. **在 XXL-Job Admin 中配置任务**

- 进入「执行器管理」，确认执行器已注册
- 进入「任务管理」，点击「新增任务」
- 填写任务信息并选择 JobHandler 名称

---

## 配置说明

### 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Fly Agent Server | 8080 | REST API 服务 |
| XXL-Job Executor | 8082 | 任务执行器 |
| XXL-Job Admin | 9090 | 任务调度管理界面 |
| Frontend Dev Server | 3000 | 前端开发服务器 |

### 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `SERVER_PORT` | 后端服务端口 | 8080 |
| `DB_HOST` | 数据库主机 | localhost |
| `DB_PORT` | 数据库端口 | 3306 |
| `DB_NAME` | 数据库名称 | fly_agent |
| `REDIS_HOST` | Redis 主机 | 127.0.0.1 |
| `REDIS_PORT` | Redis 端口 | 6379 |
| `ZHIPU_API_KEY` | 智谱 AI API Key | - |
| `GITHUB_TOKEN` | GitHub API token，用于仓库搜索和 PR 扫描 | - |
| `SWE_QWEN_BASE_URL` / `SWE_QWEN_TOKEN` / `SWE_QWEN_MODEL` | Qwen 评估模型配置 | - |
| `SWE_OPUS_BASE_URL` / `SWE_OPUS_TOKEN` / `SWE_OPUS_MODEL` | Opus pass@8 评估模型配置 | - |

---

## 相关文档

- [XXL-Job 配置指南](docs/XXL-JOB_SETUP.md) - 详细的 XXL-Job 配置和使用说明
- [Task 模块 README](fly-agent-task/README.md) - 任务调度模块详细文档
- [配置总结](CONFIGURATION_SUMMARY.md) - 项目配置说明
- [实现指南](IMPLEMENTATION_INSTRUCTIONS.md) - 开发实现指南
- [前端技术栈](docs/FRONTEND_STACK.md) - 前端技术栈说明

---

## 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源协议。

---

## 联系方式

- **项目地址**: [GitHub](https://github.com/your-org/fly-agent)
- **问题反馈**: [Issues](https://github.com/your-org/fly-agent/issues)
- **文档**: [Wiki](https://github.com/your-org/fly-agent/wiki)

---

<div align="center">

**如果这个项目对你有帮助，请给我们一个 ⭐️ Star**

Made with ❤️ by Fly Agent Team

</div>
