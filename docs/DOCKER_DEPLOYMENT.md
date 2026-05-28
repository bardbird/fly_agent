# Docker 部署说明

本文档说明前端、后端 API 和 task 服务的 Docker 部署方式。原 systemd + Nginx 部署仍可保留；Docker 部署统一使用 `scripts/docker-deploy.sh`。

## 目录约定

- 编排文件：`deploy/docker/docker-compose.yml`
- 应用镜像 Dockerfile：`deploy/docker/Dockerfile.spring`
- 前端镜像 Dockerfile：`deploy/docker/Dockerfile.web`
- 前端 Nginx 配置：`deploy/docker/nginx/default.conf`
- 部署入口：`scripts/docker-deploy.sh`
- Docker 环境文件：`deploy/docker/.env`

## 宿主机数据目录

`swe-output` 很重，不进入镜像，也不写在容器可丢失层。默认宿主机目录如下：

```bash
/data/fly-agent/swe-output
```

容器内固定路径：

```bash
/data/fly-agent/swe-output
```

应用使用：

```bash
SWE_PRODUCTION_ROOT=/data/fly-agent/swe-output
```

相关环境变量：

```bash
SWE_OUTPUT_HOST_DIR=/data/fly-agent/swe-output
FLY_AGENT_LOG_DIR=/data/fly-agent/logs
XXL_JOB_LOG_DIR=/data/fly-agent/logs/xxl-job
```

日志默认落在宿主机：

```bash
/data/fly-agent/logs/fly-agent-server.log
/data/fly-agent/logs/fly-agent-task.log
/data/fly-agent/logs/xxl-job/
```

Docker 标准输出日志仍可用：

```bash
./scripts/docker-deploy.sh logs fly-agent-server
./scripts/docker-deploy.sh logs fly-agent-task
./scripts/docker-deploy.sh logs fly-agent-web
```

## SWE-agent 放置位置

不建议把 `SWE-agent` checkout 放在当前代码目录下。它是外部运行时工具，生命周期和业务代码不同，放在仓库下会让 Docker build context、备份和发布边界都变重。

推荐宿主机位置：

```bash
/opt/fly-agent/swe-agent
```

容器内固定挂载为：

```bash
/opt/fly-agent/swe-agent
```

应用通过环境变量使用：

```bash
SWE_AGENT_ROOT=/opt/fly-agent/swe-agent
```

部署脚本会自动检查 `SWE_AGENT_HOST_ROOT`。如果目录不存在，会从 `SWE_AGENT_GIT_URL` clone；如果缺少可执行的 `sweagent`，会用 Python 3.11+ 创建 `.venv` 并执行 `pip install -e`。

相关环境变量：

```bash
SWE_AGENT_BOOTSTRAP=true
SWE_AGENT_GIT_URL=https://github.com/SWE-agent/SWE-agent.git
SWE_AGENT_GIT_REF=main
SWE_AGENT_AUTO_UPDATE=false
```

默认只在缺失时拉取，不会每次部署都更新已有目录。需要跟随远端分支时，把 `SWE_AGENT_AUTO_UPDATE=true`。

当前 `.gitignore` 已忽略 `tools/SWE-agent/`，如果本机已有旧目录，也可以手动迁移：

```bash
sudo mkdir -p /opt/fly-agent
sudo rsync -a tools/SWE-agent/ /opt/fly-agent/swe-agent/
```

确保目录下能找到以下任意一个可执行文件：

```bash
/opt/fly-agent/swe-agent/.venv/bin/sweagent
/opt/fly-agent/swe-agent/venv/bin/sweagent
/opt/fly-agent/swe-agent/sweagent
```

也可以单独执行 bootstrap：

```bash
./scripts/docker-deploy.sh bootstrap-swe-agent
```

## 初始化配置

生成环境文件：

```bash
./scripts/docker-deploy.sh init-env
```

编辑 `deploy/docker/.env`，至少确认：

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_REDIS_HOST`
- `SPRING_REDIS_PASSWORD`
- `XXL_JOB_ADMIN_ADDRESSES`
- `GITHUB_TOKEN`
- `ZHIPU_API_KEY`
- `SWE_QWEN_TOKEN`
- `SWE_OPUS_TOKEN`
- `SWE_AGENT_HOST_ROOT`
- `SWE_OUTPUT_HOST_DIR`
- `FLY_AGENT_LOG_DIR`
- `XXL_JOB_LOG_DIR`

默认配置假设 MySQL、Redis、XXL-Job Admin 在宿主机运行，容器通过 `host.docker.internal` 访问宿主机。

## 构建和启动

一键构建并启动三类服务：

```bash
./scripts/docker-deploy.sh deploy
```

只启动已构建镜像：

```bash
./scripts/docker-deploy.sh start
```

只构建某个服务：

```bash
./scripts/docker-deploy.sh build fly-agent-server
./scripts/docker-deploy.sh build fly-agent-task
./scripts/docker-deploy.sh build fly-agent-web
```

服务端口默认映射：

| 服务 | 宿主机端口 | 容器端口 | 说明 |
| --- | --- | --- | --- |
| `fly-agent-web` | `6677` | `80` | 前端静态页面和 `/api/` 反代 |
| `fly-agent-server` | `8080` | `8080` | 后端 REST API |
| `fly-agent-task` | `8082` | `8082` | task 服务 HTTP 端口 |
| `fly-agent-task` | `9999` | `9999` | XXL-Job executor 端口 |

## 启停服务

查看状态：

```bash
./scripts/docker-deploy.sh status
```

停止服务但保留容器：

```bash
./scripts/docker-deploy.sh stop
```

停止指定服务：

```bash
./scripts/docker-deploy.sh stop fly-agent-task
```

重启服务：

```bash
./scripts/docker-deploy.sh restart
```

删除本 stack 容器和网络：

```bash
./scripts/docker-deploy.sh down
```

查看日志：

```bash
./scripts/docker-deploy.sh logs
./scripts/docker-deploy.sh logs fly-agent-server
```

## 旧镜像清理

清理未被当前容器使用的 Fly Agent 应用镜像，并清理 dangling images：

```bash
./scripts/docker-deploy.sh clean-images
```

`deploy` 命令会在启动成功后自动执行一次 `clean-images`。

如果已经 `down`，并希望删除所有 Fly Agent 应用镜像：

```bash
./scripts/docker-deploy.sh clean-all-images
```

该脚本只匹配带有 `com.fly-agent.stack=app` label 的应用镜像，不会删除 MySQL、Redis、XXL-Job 等基础设施镜像。

## 健康检查

```bash
curl -I http://127.0.0.1:6677/
curl -sS http://127.0.0.1:8080/
curl -sS http://127.0.0.1:6677/api/v1/swe/tasks | head
```

## 注意事项

- `deploy/docker/.env` 不提交仓库，真实 token 只保存在部署机器。
- SWE-Pro 评测会从容器内调用 Docker，因此 compose 默认挂载 `/var/run/docker.sock`。
- 评测输出目录固定为宿主机 `/data/fly-agent/swe-output`，容器内路径也是 `/data/fly-agent/swe-output`，避免容器内再启动 Docker 时出现路径不一致。
- 如果 XXL-Job Admin 不在宿主机，需要把 `XXL_JOB_ADMIN_ADDRESSES` 改为容器可访问地址。
