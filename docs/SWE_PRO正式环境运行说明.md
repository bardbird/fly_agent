# SWE-Pro 正式环境运行说明

## 配置原则

正式环境只维护项目 YAML 配置，不要求额外手工导出模型环境变量。

模型配置写在：

- `fly-agent-server/src/main/resources/application.yml`
- `fly-agent-server/src/main/resources/application-dev.yml`

当前链路：

```text
application.yml / application-dev.yml
  -> Spring Boot 读取 swe.qwen / swe.opus
  -> SwePipelineService 注入子进程环境变量 QWEN_API_KEY / OPUS_API_KEY
  -> eval_with_swe_agent.py 从环境变量读取真实 key
  -> SWE-agent 直接接收真实 api_key 参数
```

同时，评测子进程会覆盖 `OPENAI_API_KEY` / `DASHSCOPE_API_KEY`，保证 OpenAI 兼容网关和 DashScope 兼容网关都能拿到同一份 YAML token。

也就是说，正式配置只写一份 YAML；日志会脱敏 `--agent.model.api_key`，但进程运行参数里会有真实 key，这是当前最直接可跑链路。

## 模型配置

示例：

```yaml
swe:
  qwen:
    base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
    token: sk-xxxx
    model: qwen3.6-plus-2026-04-02
    max-input-tokens: 22000

  opus:
    base-url: https://your-opus-gateway.example/v1
    token: sk-xxxx
    model: claude-opus-4-7
```

## SWE-agent step 策略

默认 `swe-agent.max-steps` 保持较小，避免每次尝试成本过高。

Opus 使用分段策略：

```yaml
swe:
  swe-agent:
    maxSteps: 20
  opus-max-steps-schedule: 180,50,10
  model-timeout-seconds: ${SWE_MODEL_TIMEOUT_SECONDS:3600}
```

含义：

- 测试阶段第一次尝试给 180 steps，允许充分探索和修复。
- 第二次尝试给 50 steps。
- 第三次及后续尝试全部降到 10 steps，控制成本。
- schedule 超过长度后复用最后一个值。

## 正式服务管理

正式环境服务由 systemd 管理：

```text
/etc/systemd/system/fly-agent-server.service
```

禁止用下面这些方式启动正式服务：

```bash
setsid -f java -jar ...
nohup java -jar ...
java -jar ... &
```

这些方式会绕开 systemd，导致进程无人托管、重启策略不一致、排障混乱。

## 构建与重启

从仓库根目录执行：

```bash
mvn -pl fly-agent-server -am -DskipTests package
sudo systemctl restart fly-agent-server.service
sudo systemctl status fly-agent-server.service --no-pager -l
```

确认 8080 正常监听：

```bash
ss -ltnp | rg ':8080'
curl -sS 'http://127.0.0.1:8080/api/v1/swe/runs/detail?runId=38' | head -c 1000
```

## 续跑 SWE-Pro pipeline

候选 `id=60` 当前任务：

```text
taskId=18
runId=38
package=/home/ubuntu/gitee/fly_agent/swe-output/production-task-windows-rs-3942
```

从 Opus 模型评测阶段续跑：

```bash
curl -sS -X POST 'http://127.0.0.1:8080/api/v1/swe/runs/start' \
  -H 'Content-Type: application/json' \
  --data '{"taskId":18,"resumeRunId":38,"resumeFromStage":"MODEL_OPUS_EVAL"}'
```

查询状态：

```bash
curl -sS 'http://127.0.0.1:8080/api/v1/swe/runs/detail?runId=38'
```

## 结果判定

模型评测中如果所有尝试都是 invalid、没有 patch、没有 submit，不能判定为任务难度合格或不合格。

这种情况属于评测基础设施或 adapter 失败，必须先修复评测链路，再重新跑模型评测。

## 当前关键修复点

- Rust 候选任务的 `pass_to_pass` 不再从 `test.patch` 中选 PR 新增/修改测试。
- `pass_to_pass` 会优先选择同 crate 中未被 PR 修改的已有 integration tests。
- SWE-agent API key 不再以真实值进入命令行参数。
- 正式服务必须用 `fly-agent-server.service` 管理。

## 2026-05-28 runId=38 续跑记录

正式服务已通过 systemd 管理：

```bash
sudo systemctl status fly-agent-server.service --no-pager -l
```

当前服务：

```text
fly-agent-server.service active/running
ExecStart=/usr/lib/jvm/java-17-openjdk-amd64/bin/java -jar /home/ubuntu/gitee/fly_agent/fly-agent-server/target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

已从 `MODEL_QWEN_EVAL` 续跑：

```bash
curl -sS -X POST 'http://127.0.0.1:8080/api/v1/swe/runs/start' \
  -H 'Content-Type: application/json' \
  --data '{"taskId":18,"resumeRunId":38,"resumeFromStage":"MODEL_QWEN_EVAL"}'
```

结果：

```text
runId=38
stage=MODEL_QWEN_EVAL
status=FAILED
reason=Qwen 模型评测基础设施失败，不能作为 pass@N 难度门控结果
statusCounts=test_infra_failed=4
```

直接用 `application-dev.yml` 中的 Qwen `base-url/token` 调用 DashScope OpenAI 兼容 `/models` 接口，返回：

```text
HTTP 401
message=You didn't provide an API key.
```

结论：

- 当前失败不是候选任务难度问题。
- 当前失败不是 Docker/local verify 问题。
- 当前失败不是 `fail_to_pass/pass_to_pass` 问题。
- 当前 Qwen YAML token 对 `https://dashscope.aliyuncs.com/compatible-mode/v1` 不可用。
- 必须先替换 `swe.qwen.token` 为可用 DashScope/OpenAI-compatible key，再续跑 `MODEL_QWEN_EVAL`。
