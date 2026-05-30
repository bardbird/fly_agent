# 配置说明文档

## 数据库配置

### MySQL 配置

**连接信息**：
- 主机: `localhost:3306`
- 数据库: `fly_agent`
- 用户名: `root`
- 密码: `123456789`

**初始化步骤**：

1. 创建数据库：
```bash
mysql -u root -p123456789 -e "CREATE DATABASE IF NOT EXISTS fly_agent CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

2. 初始化表结构：
```bash
mysql -u root -p123456789 fly_agent < scripts/init-mysql.sql
```

或者使用自动化脚本：
```bash
./scripts/init-and-start.sh
```

**验证连接**：
```bash
mysql -u root -p123456789 -e "USE fly_agent; SHOW TABLES;"
```

预期输出：
```
+-------------------------+
| Tables_in_fly_agent     |
+-------------------------+
| agent_config            |
| agent_skill             |
| agent_skill_resource    |
| conversation            |
| message                 |
+-------------------------+
```

### Redis 配置

**连接信息**：
- 主机: `127.0.0.1`
- 端口: `6379`
- 数据库: `0` (Redis使用数字索引，0-15)
- 密码: `homeX`

**验证连接**：
```bash
redis-cli -h 127.0.0.1 -p 6379 -a homeX ping
```

预期输出：`PONG`

**查看Redis信息**：
```bash
redis-cli -h 127.0.0.1 -p 6379 info
```

## 配置文件说明

### application.yml

主配置文件，包含：
- 数据源配置
- Redis配置
- 智谱AI配置
- MyBatis配置
- 日志配置

**关键配置**：
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/fly_agent
    username: root
    password: 123456789

  redis:
    host: 127.0.0.1
    port: 6379
    database: 0
```

### application-dev.yml

开发环境配置，覆盖主配置中的部分设置：
- 服务端口: `8080`
- 数据库连接
- 日志级别

### SWE-Pro 流水线配置

`swe` 配置用于 GitHub 候选采集、项目内 SWE-Pro toolkit 调用、Opus/Qwen 模型评估和任务包输出。当前流程不再读取 `swe.gpt`，也不会生成 GPT 辅助 Opus 重试提示词。

**关键配置**：
```yaml
swe:
  toolkit-root: ${SWE_TOOLKIT_ROOT:tools/swe-pro-production}
  production-root: ${SWE_PRODUCTION_ROOT:swe-output}
  python: python3
  swe-agent:
    root: ${SWE_AGENT_ROOT:tools/SWE-agent}
    max-steps: ${SWE_AGENT_MAX_STEPS:20}
  qwen-attempts: 4
  opus-attempts: 8
  opus-max-steps-schedule: 180,50,10
  model-timeout-seconds: ${SWE_MODEL_TIMEOUT_SECONDS:3600}
  github:
    token: ${GITHUB_TOKEN:}
  qwen:
    base-url: ${SWE_QWEN_BASE_URL:}
    token: ${SWE_QWEN_TOKEN:}
    model: ${SWE_QWEN_MODEL:qwen3.6-plus}
  opus:
    base-url: ${SWE_OPUS_BASE_URL:}
    token: ${SWE_OPUS_TOKEN:}
    model: ${SWE_OPUS_MODEL:claude-opus-4-7}
```

**运行要求**：
- 默认 `toolkit-root` 使用项目内 `tools/swe-pro-production`；如需覆盖，可设置 `SWE_TOOLKIT_ROOT`。
- 默认 `production-root` 使用项目根目录下的 `swe-output`；如需隔离试跑结果，可设置 `SWE_PRODUCTION_ROOT`，避免覆盖已有送检样例。
- `toolkit-root` 下必须存在 `scripts/prepare_tasks_from_candidates.py`、`scripts/resolve_runtime_env.py`、`scripts/eval_with_swe_agent.py`、`scripts/package_task.py`；`swe-agent.root` 必须指向已安装 SWE-agent 且能找到 `sweagent` CLI 的目录。
- Harness build 阶段会基于 `base_commit` 下的仓库文件、`task.json` 和测试脚本生成 `runtime_env.json`；Local verification、SWE-agent safe image 和 Docker package 优先消费该确定性环境契约。该环境依赖修复路径不接入额外大模型，后续 Opus/Qwen 模型评测仍按流水线执行。
- GitHub PR 候选必须是 merged PR，并且 PR 标题或正文包含 `closes/fixes/resolves #issue` 等关闭关键词。
- GitHub 搜索和 PR 扫描会在 `127.0.0.1:7897` 可连通时自动走该 HTTP 代理；代理不可用时直连。
- QC 阶段会生成 `乙方质检-SWE-Pro数据验收标准对照表.xlsx`，主表为 34 条验收结果，汇总表包含 patch 规模和模型采样结果；review 文件中仍含 `PENDING_*`、`待审校`、`待补充`、`待评测`、`待验证` 等占位内容时会失败。

## 启动前检查清单

- [ ] MySQL服务已启动
- [ ] Redis服务已启动
- [ ] 数据库 `fly_agent` 已创建
- [ ] 数据库表已初始化
- [ ] 智谱AI API Key已配置
- [ ] 如需运行 SWE-Pro 流水线，确认项目内 `tools/swe-pro-production` 存在，或已通过 `SWE_TOOLKIT_ROOT` 覆盖，并配置 `GITHUB_TOKEN`、Qwen 和 Opus 模型参数
- [ ] JAVA_HOME已设置为JDK 17

## 常见问题

### 1. MySQL连接失败

**错误**: `Communications link failure`

**解决方案**：
```bash
# 检查MySQL是否运行
# macOS
brew services list | grep mysql

# 启动MySQL
brew services start mysql

# 或使用系统命令
sudo /usr/local/mysql/support-files/mysql.server start
```

### 2. Redis连接失败

**错误**: `Unable to connect to Redis`

**解决方案**：
```bash
# 检查Redis是否运行
# macOS
brew services list | grep redis

# 启动Redis
brew services start redis

# 或使用命令
redis-server
```

### 3. 编译失败

**错误**: `No compiler is provided`

**解决方案**：
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
```

### 4. 端口冲突

**错误**: `Port 8080 is already in use`

**解决方案**：

修改 `application-dev.yml`:
```yaml
server:
  port: 8080  # 默认端口；如冲突，改为其他未占用端口
```

## 测试连接

启动应用后，可以通过以下方式测试连接：

1. **健康检查**：
```bash
curl http://localhost:8080/actuator/health
```

2. **测试API**：
```bash
# 获取Agent列表
curl http://localhost:8080/api/v1/agents

# 创建Agent
curl -X POST http://localhost:8080/api/v1/agents \
  -H "Content-Type: application/json" \
  -d '{
    "agentName": "test-agent",
    "agentType": "chat",
    "systemPrompt": "You are a helpful assistant."
  }'
```

## 监控和日志

### 查看应用日志

日志文件位置：
- 控制台输出：标准输出
- 文件日志：`logs/fly-agent.log`
- 日志配置：`fly-agent-server/src/main/resources/logback-spring.xml`

### MySQL慢查询日志

检查MySQL性能：
```sql
-- 查看慢查询
SHOW VARIABLES LIKE 'slow_query%';

-- 启用慢查询日志
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 2;
```

### Redis监控

```bash
# 实时监控
redis-cli -h 127.0.0.1 -p 6379 monitor

# 查看键数量
redis-cli -h 127.0.0.1 -p 6379 DBSIZE

# 查看fly_agent相关的键
redis-cli -h 127.0.0.1 -p 6379 KEYS "agent:*"
```

## 数据备份

### MySQL备份

```bash
# 备份数据库
mysqldump -u root -p123456789 fly_agent > backup/fly_agent_$(date +%Y%m%d).sql

# 恢复数据库
mysql -u root -p123456789 fly_agent < backup/fly_agent_20250205.sql
```

### Redis备份

```bash
# 手动触发RDB快照
redis-cli -h 127.0.0.1 -p 6379 BGSAVE

# 检查备份文件
redis-cli -h 127.0.0.1 -p 6379 LASTSAVE
```

## 安全建议

1. **生产环境修改密码**：
   - 不要使用 `123456789` 这种简单密码
   - 修改配置文件中的数据库密码
   - 考虑使用环境变量或配置中心

2. **API Key保护**：
   - 不要将智谱AI API Key提交到代码仓库
   - 使用环境变量：`export ZHIPU_API_KEY=your_key`

3. **网络安全**：
   - 生产环境不要绑定到 `0.0.0.0`
   - 使用防火墙限制访问
   - 启用HTTPS

4. **数据加密**：
   - 敏感数据加密存储
   - 使用SSL连接数据库
