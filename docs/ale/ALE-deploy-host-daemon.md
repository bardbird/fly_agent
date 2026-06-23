# ALE 宿主执行部署指南

ALE 采用「后端容器编排 + 宿主 daemon 执行」：后端容器写触发文件到共享卷，宿主 daemon 消费并拉起 codex/ale_run（避免 Docker-in-Docker）。

## 前置
- 宿主已 `git clone` 本仓库并 `git pull` 到最新
- Python 3、Node.js 22（codex CLI 依赖）、`uv` 已装
- ALE 框架 `agents-last-exam` 已 clone 到 `$ALE_FRAMEWORK_ROOT`（默认 `/home/ubuntu/agents-last-exam`）并 `uv sync`

## 一次性安装

### 1. 安装 codex skills 到宿主 ~/.codex/skills
`bash scripts/install-codex-skills.sh`
（装 `ale-task-factory` 等 skill；之后重启 codex 会话生效）

### 2. 安装 codex CLI（若宿主未装）
`npm install -g @openai/codex@latest` 然后 `codex --version`

### 3. 配置 codex 模型（~/.codex/config.toml）
按需配置 `model` / provider。

## 启动 daemon（持久化，必需）

Docker 部署下 `fly-agent-server` 只负责编排：写触发文件到 `/data/fly-agent/ale-runs/.queue`，然后等待 `stage1_progress.json` / `stage2_progress.json`。宿主 daemon 是实际执行器，负责消费队列并拉起 `codex`、`ale_stage1_runner.py`、`ale_stage2_runner.py` 和 ALE framework。未启动时，stage 1 会停在 `starting`/`RUNNING`，队列里会残留 `*.json`。

用 systemd 托管。当前项目默认 unit `/etc/systemd/system/ale-daemon.service`：

    [Unit]
    Description=ALE host daemon
    After=network.target docker.service
    Wants=docker.service

    [Service]
    Type=simple
    User=ubuntu
    WorkingDirectory=/home/ubuntu/gitee/fly_agent
    Environment=ALE_QUEUE_DIR=/data/fly-agent/ale-runs/.queue
    Environment=ALE_FRAMEWORK_ROOT=/home/ubuntu/agents-last-exam
    Environment=PATH=/home/ubuntu/.local/bin:/home/ubuntu/.cargo/bin:/usr/local/bin:/usr/bin:/bin
    ExecStart=/bin/bash /home/ubuntu/gitee/fly_agent/scripts/ale_daemon.sh
    Restart=always
    RestartSec=5

    [Install]
    WantedBy=multi-user.target

安装并启用：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now ale-daemon
```

查状态和日志：

```bash
systemctl status ale-daemon --no-pager
journalctl -u ale-daemon -f
```

临时跑仅用于排障，不建议作为长期部署：

```bash
nohup env \
  ALE_QUEUE_DIR=/data/fly-agent/ale-runs/.queue \
  ALE_FRAMEWORK_ROOT=/home/ubuntu/agents-last-exam \
  bash /home/ubuntu/gitee/fly_agent/scripts/ale_daemon.sh \
  > /data/fly-agent/ale-daemon.log 2>&1 &
```

## 启动后端容器
`cd deploy/docker && docker compose up -d fly-agent-server`
确认 server 容器与宿主共享 `/data/fly-agent/ale-runs`（compose volume）。

## 排障
- run 一直 RUNNING 或 stage 1 卡在 `starting`：先看 daemon 是否在跑（`systemctl status ale-daemon`），再看 `/data/fly-agent/ale-runs/.queue` 是否残留触发文件。
- daemon 已跑但没有消费：确认 unit 里的 `ALE_QUEUE_DIR` 与 `fly-agent-server` 容器环境变量 `ALE_QUEUE_DIR` 一致，且容器挂载了 `/data/fly-agent/ale-runs`。
- 触发文件被移到 `.queue-invalid/`：触发 JSON 缺少 `type`、`run_dir`，或 `type` 不是 `stage1`/`stage2`。
- stage1 codex 报 skill 找不到：`bash scripts/install-codex-skills.sh` 重装；重启 codex。
- stage1 Oracle failed "venv not ready"：`cd $ALE_FRAMEWORK_ROOT && uv sync`。
- 日志：`<run_dir>/stage1.log`、`stage2.log`、`stage{1,2}_progress.json`。
