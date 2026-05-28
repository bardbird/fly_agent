# XXL-Job 快速启动指南

## 一、XXL-Job Admin 已部署

XXL-Job Admin 容器已成功启动并运行：

- **访问地址**: http://localhost:9090/xxl-job-admin
- **默认账号**: admin
- **默认密码**: 123456
- **容器名称**: xxl-job-admin
- **镜像版本**: xuxueli/xxl-job-admin:2.4.0
- **端口映射**: 9090:8080, 9091:8081
- **自动启动**: 已配置 `--restart=always`，Docker 启动时自动启动
- **数据库**: MySQL (xxl_job)

## 二、数据库信息

已创建 `xxl_job` 数据库并初始化表结构：

```bash
# 数据库连接信息
Host: localhost:3306
Database: xxl_job
Username: root
Password: 123456789

# 已初始化的表
- xxl_job_group        # 执行器配置表
- xxl_job_info         # 任务配置表
- xxl_job_lock         # 锁表
- xxl_job_log          # 日志表
- xxl_job_log_report   # 日志报表
- xxl_job_logglue      # GLUE日志
- xxl_job_registry     # 注册表
- xxl_job_user         # 用户表
```

## 三、项目配置

### 1. fly-agent-task 模块

新增 `fly-agent-task` 模块，包含：

```
fly-agent-task/
├── src/main/resources/
│   └── application.yml             # XXL-Job 配置文件
├── config/
│   └── XxlJobConfig.java          # XXL-Job 配置类
└── job/
    ├── SimpleDemoJob.java         # 简单任务示例
    ├── ParameterDemoJob.java      # 参数化任务示例
    └── BusinessDemoJob.java       # 业务场景任务示例
```

### 2. 配置文件

已在 `fly-agent-task/src/main/resources/application.yml` 中配置 XXL-Job：

```yaml
# XXL-Job 配置
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:9090/xxl-job-admin
    accessToken: default_token
    executor:
      appname: fly-agent-executor
      port: 9999
      logpath: /data/applogs/xxl-job/jobhandler
      logretentiondays: 30
```

## 四、Demo Job 列表

### 1. 简单任务 (SimpleDemoJob)

#### demoSimpleJob
- **JobHandler**: `demoSimpleJob`
- **功能**: 打印当前时间和任务信息
- **Cron示例**: `0/5 * * * * ?` (每5秒执行)
- **参数**: 无

#### demoDataProcessJob
- **JobHandler**: `demoDataProcessJob`
- **功能**: 模拟批量数据处理
- **Cron示例**: `0 */10 * * * ?` (每10分钟执行)
- **参数**: 处理数量，如 `100`

### 2. 参数化任务 (ParameterDemoJob)

#### demoParameterJob
- **JobHandler**: `demoParameterJob`
- **功能**: JSON格式参数任务示例
- **Cron示例**: `0 0 2 * * ?` (每天凌晨2点执行)
- **参数示例**:
  ```json
  {
    "batchSize": 50,
    "timeout": 300,
    "retryTimes": 3
  }
  ```

#### demoShardingJob
- **JobHandler**: `demoShardingJob`
- **功能**: 分片广播任务示例
- **路由策略**: 分片广播
- **说明**: 适用于大数据量并行处理

### 3. 业务场景任务 (BusinessDemoJob)

#### demoDataCleanupJob
- **JobHandler**: `demoDataCleanupJob`
- **功能**: 清理过期数据
- **Cron示例**: `0 0 3 * * ?` (每天凌晨3点执行)
- **参数**: 保留天数，默认30天

#### demoStatisticsJob
- **JobHandler**: `demoStatisticsJob`
- **功能**: 生成统计报表
- **Cron示例**: `0 0 1 * * ?` (每天凌晨1点执行)
- **参数**: 统计日期，格式 `yyyy-MM-dd`

#### demoDataSyncJob
- **JobHandler**: `demoDataSyncJob`
- **功能**: 从外部系统同步数据
- **Cron示例**: `0 */30 * * * ?` (每30分钟执行)

#### demoHealthCheckJob
- **JobHandler**: `demoHealthCheckJob`
- **功能**: 系统健康检查
- **Cron示例**: `0/10 * * * * ?` (每10秒执行)
- **检查项**: 数据库、Redis、磁盘、内存

#### demoNotificationJob
- **JobHandler**: `demoNotificationJob`
- **功能**: 定时发送通知
- **Cron示例**: `0 0 9,18 * * ?` (每天9点和18点执行)

## 五、启动应用

### 1. 编译打包

```bash
# 设置 JAVA_HOME
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home

# 编译项目
mvn clean package -DskipTests
```

### 2. 启动服务

```bash
# 方式1：使用 Maven 插件
cd fly-agent-server
mvn spring-boot:run

# 方式2：使用 JAR 包
java -jar fly-agent-server/target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

### 3. 验证执行器注册

启动应用后，查看日志确认执行器成功注册到 XXL-Job Admin：

```
>>>>>>>>>>>> xxl-job config init.
>>>>>>>>>>>> xxl-job remoting server start success, port:{9999}
```

## 六、配置任务

### 1. 登录 XXL-Job Admin

访问 http://localhost:9090/xxl-job-admin，使用 admin/123456 登录。

### 2. 查看执行器

进入「执行器管理」页面，应该能看到自动注册的 `fly-agent-executor` 执行器。

### 3. 添加任务

进入「任务管理」页面，点击「新增任务」：

**示例1：简单任务**
- 执行器：`fly-agent-executor`
- 任务描述：`简单任务示例`
- Cron：`0/10 * * * * ?`
- 运行模式：BEAN
- JobHandler：`demoSimpleJob`

**示例2：参数化任务**
- 执行器：`fly-agent-executor`
- 任务描述：`参数化任务示例`
- Cron：`0 */5 * * * ?`
- 运行模式：BEAN
- JobHandler：`demoParameterJob`
- 任务参数：`{"batchSize": 20, "timeout": 100}`

**示例3：业务任务**
- 执行器：`fly-agent-executor`
- 任务描述：`数据清理任务`
- Cron：`0 0 3 * * ?`
- 运行模式：BEAN
- JobHandler：`demoDataCleanupJob`
- 任务参数：`7` (保留7天数据)

### 4. 执行任务

- **执行一次**: 点击「执行一次」按钮立即执行任务
- **启动任务**: 点击「启动」按钮，任务将按照 Cron 表达式定时执行
- **查看日志**: 点击「查看日志」查看任务执行历史和详细日志

## 七、查看日志

### 1. XXL-Job Admin 日志

在 XXL-Job Admin 的「调度日志」页面可以查看：
- 调度时间
- 调度结果
- 执行日志
- 耗时统计

### 2. 应用日志

在应用日志中可以查看详细的执行日志：

```bash
# 查看应用日志
tail -f logs/fly-agent.log

# 过滤 XXL-Job 相关日志
grep "xxl-job" logs/fly-agent.log
```

## 八、停止服务

### 1. 停止应用

```bash
# 如果使用 Maven 插件启动，按 Ctrl+C 停止

# 如果使用 JAR 包启动，查找进程并停止
ps aux | grep fly-agent-server
kill <pid>
```

### 2. 停止 XXL-Job Admin（可选）

```bash
docker stop xxl-job-admin

# 重新启动
docker start xxl-job-admin
```

## 九、常见问题

### 1. 执行器无法注册

**问题**: 在 XXL-Job Admin 看不到执行器

**解决方案**:
- 检查 `xxl.job.admin.addresses` 配置是否正确
- 确认 XXL-Job Admin 已启动并可访问
- 检查应用日志是否有错误信息
- 确认网络连接正常

### 2. 任务执行失败

**问题**: 任务在 XXL-Job Admin 显示执行失败

**解决方案**:
- 查看调度日志中的详细错误信息
- 检查应用日志中的异常堆栈
- 确认 JobHandler 名称正确
- 检查任务参数格式是否正确

### 3. 端口冲突

**问题**: 启动时提示端口 9999 被占用

**解决方案**:
- 修改 `application.yml` 中的 `xxl.job.executor.port` 配置
- 或者停止占用端口的其他进程

## 十、容器管理命令

```bash
# 查看 XXL-Job Admin 容器状态
docker ps | grep xxl-job-admin

# 查看容器日志
docker logs xxl-job-admin

# 重启容器
docker restart xxl-job-admin

# 停止容器
docker stop xxl-job-admin

# 删除容器
docker rm xxl-job-admin

# 重新创建容器
docker run -d --name xxl-job-admin --restart=always \
  -p 9090:8080 \
  -p 9091:8081 \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai \
  --spring.datasource.username=root \
  --spring.datasource.password=123456789" \
  -e JAVA_OPTS="-Djavax.xml.accessExternalDTD=all" \
  xuxueli/xxl-job-admin:2.4.0
```

## 十一、开发自定义 Job

### 1. 创建 Job 类

在 `fly-agent-task` 模块中创建新类：

```java
@Slf4j
@Component
public class MyCustomJob {

    @XxlJob("myCustomJobHandler")
    public void execute() {
        log.info("执行自定义任务");

        try {
            // 获取任务参数
            String param = XxlJobHelper.getJobParam();

            // 业务逻辑
            doSomething();

            // 设置成功结果
            XxlJobHelper.handleSuccess("任务执行成功");
        } catch (Exception e) {
            log.error("任务执行失败", e);
            XxlJobHelper.handleFail("任务执行失败: " + e.getMessage());
        }
    }
}
```

### 2. 在 XXL-Job Admin 中配置

1. 进入「任务管理」
2. 新增任务
3. JobHandler 填写：`myCustomJobHandler`
4. 配置 Cron 和其他参数
5. 保存并执行

## 十二、参考文档

- XXL-Job 官方文档: https://www.xuxueli.com/xxl-job/
- XXL-Job GitHub: https://github.com/xuxueli/xxl-job
- Fly Agent Task README: `fly-agent-task/README.md`

---

**注意**:
1. 确保 XXL-Job Admin 和应用网络互通
2. 执行器端口（默认9999）需要在防火墙开放
3. 任务方法必须是 public void 无参方法
4. 建议生产环境修改默认访问令牌 (accessToken)
