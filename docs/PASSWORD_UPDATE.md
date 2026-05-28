# Redis密码配置更新说明

## 更新内容

已将Redis密码配置更新为：`homeX`

## 更新的文件

### 配置文件
1. ✅ `fly-agent-server/src/main/resources/application.yml`
2. ✅ `fly-agent-server/src/main/resources/application-dev.yml`

### 文档文件
3. ✅ `README.md`
4. ✅ `docs/CONFIGURATION.md`
5. ✅ `docs/QUICKSTART.md`
6. ✅ `CONFIGURATION_SUMMARY.md`

## 配置信息

### MySQL
```yaml
主机: localhost:3306
数据库: fly_agent
用户名: root
密码: 123456789
```

### Redis
```yaml
主机: 127.0.0.1
端口: 6379
数据库: 0
密码: homeX
```

## 验证Redis连接

使用密码连接Redis：
```bash
redis-cli -h 127.0.0.1 -p 6379 -a homeX ping
```

预期输出：
```
PONG
```

查看Redis信息：
```bash
redis-cli -h 127.0.0.1 -p 6379 -a homeX info
```

查看数据库中的键：
```bash
redis-cli -h 127.0.0.1 -p 6379 -a homeX KEYS "*"
```

## Spring Boot配置

Redis配置已正确设置：
```yaml
spring:
  redis:
    host: 127.0.0.1
    port: 6379
    database: 0
    password: homeX
    timeout: 3000
```

Spring Boot会自动使用此密码连接Redis。

## 启动应用

配置已更新，可以直接启动应用：

```bash
# 设置Java环境
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

# 编译并启动
mvn clean package -DskipTests
java -jar fly-agent-server/target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

或使用自动化脚本：
```bash
./scripts/init-and-start.sh
```

## 故障排查

### Redis认证失败

如果启动时出现Redis认证错误：

```
WRONGPASS invalid username-password pair
```

**解决方案**：
1. 确认Redis配置的密码是否为 `homeX`
2. 检查Redis配置文件（通常在 `/usr/local/etc/redis.conf` 或 `/etc/redis/redis.conf`）
3. 修改Redis配置文件中的 `requirepass` 为 `homeX`
4. 重启Redis服务：

```bash
# macOS
brew services restart redis

# Linux
sudo systemctl restart redis
```

### 设置Redis密码

如果Redis还没有设置密码，可以按以下步骤设置：

1. 连接到Redis：
```bash
redis-cli -h 127.0.0.1 -p 6379
```

2. 设置密码：
```bash
CONFIG SET requirepass homeX
```

3. 验证密码：
```bash
AUTH homeX
PING
```

4. 持久化配置（修改配置文件）：
```bash
# 编辑Redis配置文件
vim /usr/local/etc/redis.conf

# 添加或修改以下行
requirepass homeX

# 重启Redis
brew services restart redis
```

## 安全提醒

⚠️ **注意**：
- 当前密码 `homeX` 仅用于开发环境
- 生产环境请使用更复杂的密码
- 建议定期更换密码
- 考虑使用环境变量管理敏感信息

## 相关文档

- [详细配置说明](./CONFIGURATION.md)
- [快速参考](./QUICKSTART.md)
- [配置完成报告](../CONFIGURATION_SUMMARY.md)
