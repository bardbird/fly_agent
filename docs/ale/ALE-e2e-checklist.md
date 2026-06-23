# ALE 端到端验证清单

在宿主 + 后端容器部署完成后，按序勾选。本清单需真实环境（ALE 框架/claude code 凭证），无法在 CI 自动化。

## 环境就绪
- [ ] 宿主 daemon 运行：`systemctl status ale-daemon`（active）
- [ ] 后端容器运行：`docker compose ps`（fly-agent-server Up）
- [ ] 共享卷一致：容器内 `ls /data/fly-agent/ale-runs/.queue` == 宿主 `ls`
- [ ] codex skill 已装：`ls ~/.codex/skills/ale-task-factory/SKILL.md`
- [ ] ALE venv 就绪：`cd $ALE_FRAMEWORK_ROOT && uv run python -c "import cua_bench"`
- [ ] claude code 可用：`claude --version` 且凭证已配

## Stage1（任务工厂）
- [ ] 前端「启动生成」→ run 进入 RUNNING
- [ ] 进度条推进（不卡 20%）：`<run_dir>/stage1_progress.json` 的 percent 变化
- [ ] 日志可见：stage1 面板实时滚动 codex 输出
- [ ] 完成：run COMPLETED，`tasks/*/main.py` + `oracle-evidence.json` 生成
- [ ] task_id 与 DB ale_task.task_id exact 一致（无模糊匹配）

## Stage2（测评执行）
- [ ] 前端「开始测评」→ stage2_status RUNNING，自动切 stage2 面板
- [ ] 日志可见：stage2 面板实时滚动 ale_run 输出（不再只有进度条）
- [ ] 每任务完成：`results/<task>/result.json` + `stage2_progress.json` percent 推进
- [ ] 完成：stage2_status COMPLETED，`stage2_summary.json` 含 avg_score

## 失败路径（严格契约）
- [ ] stage2 无 verified 任务 → run FAILED（phase=failed），不跑任何任务（验证 fallback 删除）
- [ ] stage1 task ID 不匹配 → run FAILED（exact 契约）
- [ ] daemon 缺失/崩溃 → run 超时 FAILED（gateway 兜底，不永久 RUNNING）
- [ ] stage2 re-trigger → 新一轮不读旧 stage2_progress（dispatch 重置 starting）

## 日志/进度文件
- [ ] `<run_dir>/stage1_progress.json` 与 `stage2_progress.json` 独立（互不污染）
- [ ] `<run_dir>/stage1.log` / `stage2.log` 各自由 daemon 重定向（无交错）
