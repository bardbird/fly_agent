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

## 启动 daemon（持久化）

建议用 systemd 托管。示例 unit `/etc/systemd/system/ale-daemon.service`：

    [Unit]
    Description=ALE host daemon
    After=network.target

    [Service]
    Type=simple
    WorkingDirectory=/home/ubuntu/fly_agent
    Environment=ALE_QUEUE_DIR=/data/fly-agent/ale-runs/.queue
    Environment=ALE_FRAMEWORK_ROOT=/home/ubuntu/agents-last-exam
    ExecStart=/bin/bash /home/ubuntu/fly_agent/scripts/ale_daemon.sh
    Restart=always
    User=ubuntu

    [Install]
    WantedBy=multi-user.target

启用：`sudo systemctl daemon-reload && sudo systemctl enable --now ale-daemon`，查日志 `sudo journalctl -u ale-daemon -f`。

或临时跑：`nohup bash scripts/ale_daemon.sh > /data/fly-agent/ale-daemon.log 2>&1 &`

## 启动后端容器
`cd deploy/docker && docker compose up -d fly-agent-server`
确认 server 容器与宿主共享 `/data/fly-agent/ale-runs`（compose volume）。

## 排障
- run 一直 RUNNING：daemon 是否在跑（`systemctl status ale-daemon`）？查 `.queue-invalid/` 有无毒丸触发文件。
- stage1 codex 报 skill 找不到：`bash scripts/install-codex-skills.sh` 重装；重启 codex。
- stage1 Oracle failed "venv not ready"：`cd $ALE_FRAMEWORK_ROOT && uv sync`。
- 日志：`<run_dir>/stage1.log`、`stage2.log`、`stage{1,2}_progress.json`。
