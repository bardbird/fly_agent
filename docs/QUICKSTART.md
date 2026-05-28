# 快速配置参考

## 一键启动（推荐）

```bash
# 方式1: 使用自动化脚本
./scripts/init-and-start.sh

# 方式2: 手动执行
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

# 初始化数据库
mysql -u root -p123456789 < scripts/init-mysql.sql

# 编译并启动
mvn clean package -DskipTests
java -jar fly-agent-server/target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

## 配置信息速查

### MySQL
```yaml
Host: localhost:3306
Database: fly_agent
Username: root
Password: 123456789
```

### Redis
```yaml
Host: 127.0.0.1
Port: 6379
Database: 0
Password: homeX
```

### 应用服务
```yaml
Port: 8080
URL: http://localhost:8080
```

## API端点

### Agent管理
```bash
# 创建Agent
POST /api/v1/agents
Content-Type: application/json
{
  "agentName": "my-agent",
  "agentType": "chat",
  "systemPrompt": "You are a helpful assistant."
}

# 查询Agent
GET /api/v1/agents/{id}

# Agent列表
GET /api/v1/agents

# 启动Agent
POST /api/v1/agents/{id}/start

# 停止Agent
POST /api/v1/agents/{id}/stop
```

### 对话接口
```bash
# 创建对话
POST /api/v1/chat/conversations?agentId=1&userId=user001

# 发送消息
POST /api/v1/chat/completions?sessionId=xxx
Content-Type: application/json
"Hello, how are you?"
```

## 故障排查

| 问题 | 解决方案 |
|------|---------|
| MySQL连接失败 | `brew services start mysql` |
| Redis连接失败 | `brew services start redis` |
| 编译失败 | `export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home` |
| 端口占用 | 修改`application-dev.yml`中的`server.port` |

## 目录结构

```
fly-agent-platform/
├── docs/                           # 文档
│   ├── CONFIGURATION.md            # 详细配置说明
│   └── QUICKSTART.md              # 本文件
├── scripts/                        # 脚本
│   ├── init-mysql.sql             # 数据库初始化
│   ├── init-and-start.sh          # 自动化启动脚本
│   └── init-and-start.bat         # Windows启动脚本
├── fly-agent-server/              # 启动模块
│   └── src/main/resources/
│       ├── application.yml        # 主配置
│       └── application-dev.yml    # 开发环境配置
├── skills/                        # Skill文件系统
└── pom.xml                        # Maven配置
```

## 修改配置

如需修改配置，编辑以下文件：

1. **数据库配置**: `fly-agent-server/src/main/resources/application.yml`
2. **开发环境**: `fly-agent-server/src/main/resources/application-dev.yml`
3. **智谱AI Key**: `application.yml` 中的 `agent.zhipu.api-key`

详细说明请参考: [CONFIGURATION.md](./CONFIGURATION.md)
