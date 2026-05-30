# Fly Agent Task 模块

基于 XXL-Job 2.4.0 的任务调度模块。

## 功能特性

- ✅ 集成 XXL-Job 分布式任务调度
- ✅ 支持多种任务类型（简单任务、参数化任务、分片任务）
- ✅ 提供丰富的业务场景示例
- ✅ 完整的任务执行日志和监控

## 模块结构

```
fly-agent-task/
├── src/main/java/com/fly/agent/task/
│   ├── config/              # 配置类
│   │   └── XxlJobConfig.java
│   └── job/                 # 任务示例
│       ├── SimpleDemoJob.java         # 简单任务示例
│       ├── ParameterDemoJob.java      # 参数化任务示例
│       └── BusinessDemoJob.java       # 业务场景任务示例
└── pom.xml
```

## Demo Job 列表

### 1. 简单任务示例 (SimpleDemoJob)

#### demoSimpleJob
- **功能**: 打印当前时间和任务信息
- **JobHandler**: `demoSimpleJob`
- **Cron 示例**: `0/5 * * * * ?` (每5秒执行)
- **任务参数**: 无

#### demoDataProcessJob
- **功能**: 模拟批量数据处理
- **JobHandler**: `demoDataProcessJob`
- **Cron 示例**: `0 */10 * * * ?` (每10分钟执行)
- **任务参数**: 处理数量，如 `100`

### 2. 参数化任务示例 (ParameterDemoJob)

#### demoParameterJob
- **功能**: 演示如何使用 JSON 格式参数
- **JobHandler**: `demoParameterJob`
- **Cron 示例**: `0 0 2 * * ?` (每天凌晨2点执行)
- **任务参数示例**:
  ```json
  {
    "batchSize": 50,
    "timeout": 300,
    "retryTimes": 3,
    "targetDate": "2025-02-05"
  }
  ```

#### demoShardingJob
- **功能**: 分片广播任务示例
- **JobHandler**: `demoShardingJob`
- **路由策略**: 分片广播
- **说明**: 适用于大数据量并行处理场景

### 3. 业务场景任务示例 (BusinessDemoJob)

#### demoDataCleanupJob
- **功能**: 数据清理，删除过期数据
- **JobHandler**: `demoDataCleanupJob`
- **Cron 示例**: `0 0 3 * * ?` (每天凌晨3点执行)
- **任务参数**: 保留天数，默认 `30`

#### demoStatisticsJob
- **功能**: 生成统计报表
- **JobHandler**: `demoStatisticsJob`
- **Cron 示例**: `0 0 1 * * ?` (每天凌晨1点执行)
- **任务参数**: 统计日期，格式 `yyyy-MM-dd`，默认昨天

#### demoDataSyncJob
- **功能**: 从外部系统同步数据
- **JobHandler**: `demoDataSyncJob`
- **Cron 示例**: `0 */30 * * * ?` (每30分钟执行)
- **任务参数**: 同步配置（JSON格式）

#### demoHealthCheckJob
- **功能**: 系统健康检查
- **JobHandler**: `demoHealthCheckJob`
- **Cron 示例**: `0/10 * * * * ?` (每10秒执行)
- **检查项**: 数据库、Redis、磁盘、内存

#### demoNotificationJob
- **功能**: 定时发送通知（邮件、短信、站内信）
- **JobHandler**: `demoNotificationJob`
- **Cron 示例**: `0 0 9,18 * * ?` (每天9点和18点执行)
- **任务参数**: 通知配置

### 4. SWE-Pro 发现扫描任务

#### sweRepoBlacklistImportJob
- **功能**: 初始化 `swe_repo_blacklist` 表，并从 Excel 导入 repo 黑名单
- **JobHandler**: `sweRepoBlacklistImportJob`
- **任务参数示例**:
  ```json
  {
    "path": "~/Downloads/swe_existing_dataset_blacklist.xlsx"
  }
  ```

#### sweRepoDiscoveryScanJob
- **功能**: 按语言和 star 区间发现 GitHub repo，按 repo 黑名单和 SCA 许可证硬门槛过滤后扫描 merged PR 候选
- **JobHandler**: `sweRepoDiscoveryScanJob`
- **Cron 示例**: `0 0 */6 * * ?` (每6小时执行)
- **任务参数示例**:
  ```json
  {
    "languages": ["python", "java"],
    "minStars": 100,
    "startStars": 10000,
    "repositoryPerPage": 20,
    "repositoryPages": 1,
    "repoLimit": 10,
    "pullLimit": 3,
    "days": 365,
    "useStarCursor": true
  }
  ```
- **去重方式**: `useStarCursor=true` 时任务会记录每个语言/关键词/star区间的扫描游标；下一次从上轮最低已见 star 往下扫，避免反复扫描同一批高 star repo。
- **SCA硬门槛**: 扫描 PR 前会生成 `swe_repo_sca_report` 软件成分分析报告，列明 repo 源码组件及 SPDX 许可证；只有 `MIT`、`Apache-2.0`、`BSD-2-Clause`、`BSD-3-Clause`、`ISC`、`0BSD`、`Unlicense`、`CC0-1.0`、`Zlib` 进入候选扫描，未知许可证、无许可证和 copyleft/reciprocal 许可证按商业 AI 训练兼容性风险拒绝。

#### sweRepoScaDiscoveryJob
- **功能**: 按语言和 star 游标发现 GitHub repo，只落 SCA/license 报告，不做 PR 扫描
- **JobHandler**: `sweRepoScaDiscoveryJob`
- **默认语言**: `c,c++,ruby,rust,go,javascript,php,typescript,python,java`
- **必填参数**:
  ```json
  {
    "githubToken": "ghp_xxx"
  }
  ```
- **默认行为**: 每种语言每天最多新增处理 `repoLimit=10` 个 repo；同一自然日重复触发会扣减当天已写入的 SCA 报告数，确保后续触发继续拿新仓库。
- **生产参数建议**:
  ```json
  {
    "githubToken": "ghp_xxx",
    "languages": ["python"],
    "minStars": 3000,
    "maxStars": 10000,
    "dailyRepoLimit": 100,
    "useStarCursor": true
  }
  ```
- **分页说明**: SCA discovery 内部固定按 GitHub search 每页 50 条拉取，并按当天剩余额度自动计算页数；`repoLimit` 仍兼容旧参数，推荐新任务使用语义更明确的 `dailyRepoLimit`。

#### sweRepoCandidateBackfillJob
- **功能**: 从 SCA 允许的 repo 池中回填 issue-grounded merged PR 候选
- **JobHandler**: `sweRepoCandidateBackfillJob`
- **必填参数**:
  ```json
  {
    "githubToken": "ghp_xxx"
  }
  ```
- **默认行为**: 默认扫描全部支持语言，每种语言每天最多尝试 `repoLimit=10` 个 SCA allow repo；当天已经尝试过 candidate backfill 的 repo 会跳过，第二天重新计数。
- **候选硬门槛**: 回填调用 `GithubPullCandidateService.scanMergedPulls`，会执行 PR 元数据、变更文件、PR/issue 描述和评论中的上传、认证、云服务、依赖变更多、仓库过重等过滤逻辑。
- **批量补齐任务**: 可使用 `scripts/upsert-swe-xxl-language-jobs.sql` 在 XXL-Job 数据库中补齐 10 种语言的 SCA discovery 和 candidate backfill 任务；执行前替换脚本里的 `REPLACE_WITH_GITHUB_TOKEN`。

## 配置说明

### application.yml 配置示例

```yaml
# XXL-Job 配置
xxl:
  job:
    admin:
      addresses: http://127.0.0.1:8080/xxl-job-admin
    accessToken: default_token
    executor:
      appname: fly-agent-executor
      address:
      ip:
      port: 9999
      logpath: /data/applogs/xxl-job/jobhandler
      logretentiondays: 30
```

### 配置项说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| xxl.job.admin.addresses | XXL-Job Admin 地址 | http://127.0.0.1:8080/xxl-job-admin |
| xxl.job.accessToken | 访问令牌 | 空 |
| xxl.job.executor.appname | 执行器名称 | fly-agent-executor |
| xxl.job.executor.port | 执行器端口 | 9999 |
| xxl.job.executor.logpath | 日志路径 | /data/applogs/xxl-job/jobhandler |
| xxl.job.executor.logretentiondays | 日志保留天数 | 30 |

## 快速开始

### 1. 启动 XXL-Job Admin

```bash
# 拉取镜像
docker pull xuxueli/xxl-job-admin:2.4.0

# 启动容器
docker run -d --name xxl-job-admin \
  -p 8080:8080 \
  -p 8081:8081 \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai \
  --spring.datasource.username=root \
  --spring.datasource.password=123456789" \
  -e JAVA_OPTS="-Djavax.xml.accessExternalDTD=all" \
  xuxueli/xxl-job-admin:2.4.0
```

### 2. 初始化数据库

```bash
# 下载 SQL 脚本
curl -o xxl-job.sql https://raw.githubusercontent.com/xuxueli/xxl-job/master/doc/db/tables_xxl_job.sql

# 创建数据库并导入
mysql -u root -p -e "CREATE DATABASE xxl_job DEFAULT CHARACTER SET utf8mb4;"
mysql -u root -p xxl_job < xxl-job.sql
```

### 3. 访问 XXL-Job Admin

- **地址**: http://localhost:8080/xxl-job-admin
- **默认账号**: admin
- **默认密码**: 123456

### 4. 配置执行器

在 XXL-Job Admin 中添加执行器：

1. 进入「执行器管理」页面
2. 点击「新增执行器」
3. 填写信息：
   - **AppName**: `fly-agent-executor`
   - **名称**: `Fly Agent 执行器`
   - **注册方式**: 自动注册
   - **机器地址**: 空（自动注册）

### 5. 配置任务

在 XXL-Job Admin 中添加任务：

1. 进入「任务管理」页面
2. 点击「新增任务」
3. 填写信息：
   - **执行器**: `Fly Agent 执行器`
   - **任务描述**: 如 `简单任务示例`
   - **JobHandler**: 如 `demoSimpleJob`
   - **Cron**: 如 `0/5 * * * * ?`
   - **运行模式**: BEAN
   - **任务参数**: 根据需要填写

### 6. 启动应用

```bash
# 编译项目
mvn clean package

# 启动应用
cd fly-agent-server
java -jar target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

### 7. 执行任务

1. 在 XXL-Job Admin 的「任务管理」页面
2. 找到配置的任务
3. 点击「执行一次」测试，或启用任务按 Cron 表达式执行

## 开发指南

### 创建新的 Job

1. 在 `com.fly.agent.task.job` 包下创建新类
2. 添加 `@Component` 和 `@Slf4j` 注解
3. 使用 `@XxlJob("jobHandlerName")` 注解标记方法
4. 在方法中实现业务逻辑

示例：

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

### 任务执行上下文

通过 `XxlJobHelper` 获取任务上下文信息：

```java
// 获取任务参数
String param = XxlJobHelper.getJobParam();

// 获取分片信息
int shardIndex = XxlJobHelper.getShardingVo().getIndex();
int shardTotal = XxlJobHelper.getShardingVo().getTotal();

// 设置任务结果
XxlJobHelper.handleSuccess("成功");
XxlJobHelper.handleFail("失败原因");

// 记录日志
XxlJobHelper.log("自定义日志");
```

## 最佳实践

1. **任务幂等性**: 确保任务重复执行不会产生副作用
2. **超时控制**: 设置合理的任务超时时间
3. **异常处理**: 捕获所有异常并使用 `XxlJobHelper.handleFail()` 记录
4. **日志记录**: 使用 `@Slf4j` 记录详细的执行日志
5. **分片处理**: 大数据量任务使用分片广播模式
6. **参数校验**: 对任务参数进行合法性校验

## 注意事项

1. 确保 XXL-Job Admin 和 Executor 网络互通
2. Executor 端口（默认9999）需要在防火墙开放
3. 日志路径需要有写权限
4. 任务方法必须是 public void 无参方法
5. 避免在任务中使用 Thread.sleep，如需等待使用合理的间隔

## 故障排查

### 执行器无法注册

1. 检查 `xxl.job.admin.addresses` 配置是否正确
2. 确认 XXL-Job Admin 已启动
3. 检查网络连接和防火墙设置

### 任务执行失败

1. 查看 XXL-Job Admin 的调度日志
2. 查看执行器的应用日志
3. 检查任务参数格式是否正确
4. 确认任务方法无编译错误

## 许可证

Apache License 2.0
