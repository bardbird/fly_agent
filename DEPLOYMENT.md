# Fly Agent Deployment Runbook

本文档记录 Fly Agent 在当前服务器上的标准启动流程。正式对外服务不使用 `tmux`、`npm run dev` 或 Vite dev server。

Docker 化部署请看 [docs/DOCKER_DEPLOYMENT.md](docs/DOCKER_DEPLOYMENT.md)。该方式覆盖前端、后端 API 和 task 服务，并提供统一启停与旧镜像清理脚本。

## 当前部署形态

- 前端：`npm run build` 后发布静态文件到 `/var/www/fly-agent-web`，由 Nginx 对外提供服务。
- 后端：Spring Boot jar 由 systemd 服务 `fly-agent-server.service` 管理。
- 对外入口：`http://51.161.15.134:6677/`
- API 反代：Nginx 将 `/api/` 代理到 `http://127.0.0.1:8080/api/`
- Nginx 配置：`/etc/nginx/conf.d/fly-agent-web-6677.conf`
- 后端 systemd 配置：`/etc/systemd/system/fly-agent-server.service`

## 初次启动流程

### 1. 检查基础依赖

```bash
java -version
mvn -version
node -v
npm -v
nginx -v
```

当前后端使用 Java 17。数据库、Redis、模型网关、GitHub token 等配置来自 Spring 配置文件或环境变量，启动前需要确认这些依赖可用。

### 2. 构建后端

```bash
cd /home/ubuntu/gitee/fly_agent
mvn clean package -DskipTests
```

构建产物：

```text
fly-agent-server/target/fly-agent-server-1.0.0-SNAPSHOT.jar
```

### 3. 安装后端 systemd 服务

创建 `/etc/systemd/system/fly-agent-server.service`：

```ini
[Unit]
Description=Fly Agent Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=ubuntu
Group=ubuntu
WorkingDirectory=/home/ubuntu/gitee/fly_agent
ExecStart=/usr/lib/jvm/java-17-openjdk-amd64/bin/java -jar /home/ubuntu/gitee/fly_agent/fly-agent-server/target/fly-agent-server-1.0.0-SNAPSHOT.jar
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
```

加载并启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now fly-agent-server
sudo systemctl status fly-agent-server --no-pager
```

### 4. 构建前端

```bash
cd /home/ubuntu/gitee/fly_agent/fly-agent-web
npm ci
npm run build
```

### 5. 发布前端静态文件

```bash
sudo mkdir -p /var/www/fly-agent-web
sudo rsync -a --delete /home/ubuntu/gitee/fly_agent/fly-agent-web/dist/ /var/www/fly-agent-web/
```

### 6. 配置 Nginx

创建 `/etc/nginx/conf.d/fly-agent-web-6677.conf`：

```nginx
server {
    listen 6677;
    server_name _;

    root /var/www/fly-agent-web;
    index index.html;

    client_max_body_size 100m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_send_timeout 300s;
    }

    location /assets/ {
        try_files $uri =404;
        expires 30d;
        add_header Cache-Control "public, immutable";
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

校验并启动 Nginx：

```bash
sudo nginx -t
sudo systemctl enable --now nginx
sudo systemctl reload nginx
```

### 7. 验证服务

```bash
curl -I http://127.0.0.1:6677/
curl -sS http://127.0.0.1:8080/
curl -sS http://127.0.0.1:6677/api/v1/swe/tasks | head
curl -I http://51.161.15.134:6677/
```

预期结果：

- 前端首页返回 `200 OK`，响应头中 `Server` 为 `nginx`。
- 后端根接口返回 `SUCCESS`。
- `/api/v1/swe/tasks` 可以通过 `6677` 访问到后端数据。

## 日常启动流程

### 查看服务状态

```bash
sudo systemctl status fly-agent-server --no-pager
sudo systemctl status nginx --no-pager
ss -ltnp | rg ':6677|:8080'
```

### 启动服务

```bash
sudo systemctl start fly-agent-server
sudo systemctl start nginx
```

### 停止服务

```bash
sudo systemctl stop fly-agent-server
sudo systemctl stop nginx
```

### 重启服务

后端重启：

```bash
sudo systemctl restart fly-agent-server
```

前端静态文件或 Nginx 配置更新后：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### 更新后端代码并发布

```bash
cd /home/ubuntu/gitee/fly_agent
mvn clean package -DskipTests
sudo systemctl restart fly-agent-server
sudo systemctl status fly-agent-server --no-pager
curl -sS http://127.0.0.1:8080/
```

### 更新前端代码并发布

```bash
cd /home/ubuntu/gitee/fly_agent/fly-agent-web
npm ci
npm run build
sudo rsync -a --delete /home/ubuntu/gitee/fly_agent/fly-agent-web/dist/ /var/www/fly-agent-web/
sudo nginx -t
sudo systemctl reload nginx
curl -I http://127.0.0.1:6677/
```

### 查看日志

后端日志：

```bash
sudo journalctl -u fly-agent-server -f
sudo journalctl -u fly-agent-server -n 200 --no-pager
```

Nginx 日志：

```bash
sudo tail -f /var/log/nginx/access.log
sudo tail -f /var/log/nginx/error.log
```

### 标准健康检查

```bash
systemctl is-active fly-agent-server nginx
curl -I http://127.0.0.1:6677/
curl -sS http://127.0.0.1:8080/
curl -sS http://127.0.0.1:6677/api/v1/swe/tasks | head
```

## 禁止用于正式服务的启动方式

以下方式只适合本地开发或临时排查，不用于正式对外服务：

```bash
tmux new-session ...
npm run dev
npx vite
vite --host ...
java -jar ... &
```

正式服务统一使用：

```bash
sudo systemctl restart fly-agent-server
sudo systemctl reload nginx
```
