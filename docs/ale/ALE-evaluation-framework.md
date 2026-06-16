# Agents' Last Exam (ALE) 标准评测框架

> 本文档整理自 ALE 官方仓库、论文与官方文档，介绍其开源评测框架的组成、运行机制与接入方式。

## 论文与官方资源

| 项目                      | 链接 |
|-------------------------|---|
| **GitHub 仓库**           | https://github.com/rdi-berkeley/agents-last-exam |
| **官方文档**                | https://agents-last-exam.org/docs |
| **官方 Demo**             | https://agents-last-exam.org/demo |
| **Leaderboard**         | https://agenthle.org/leaderboard |
| **arXiv 论文**            | https://arxiv.org/abs/2606.05405 |
| **Hugging Face Papers** | https://huggingface.co/papers/2606.05405 |

- **主导方**：UC Berkeley RDI × RDI Foundation，由 Dawn Song 团队牵头，联合 300+ 行业专家共建
- **许可证**：软件 Apache-2.0；数据 CC-BY-4.0
- **定位**：目前覆盖最广的 Agent 评测基准，测**长时程、有经济价值、结果可验证**的真实专业任务

---

## 一、框架核心：`ale_run` 工具包

仓库本身就是一个**开源端到端评测工具**，包含：

| 能力 | 说明 |
|---|---|
| **Sandbox Provisioning** | 自动启动真实云虚拟机（当前支持 GCP，可享 $300 免费额度），含 Windows 或 Linux 全 OS + 真实专业软件 + 任务真实数据 |
| **Agent Execution** | 在 sandbox 中跑被测 agent，让其自主完成 |
| **Grading** | 用隐藏 reference + 确定性 grader 打分，分数 ∈ [0, 1] |
| **150 参考任务** | 当前公开子集（属于 1500+ 总任务库），覆盖 55 个行业 |
| **两个参考 harness** | 用于对照测试 |

---

## 二、ALE 的"运行单元"：三件套

每次实验都是 **Agent × Environment × Task** 的组合：

```
provision sandbox          ← 启动真实 VM
      ↓
stage task inputs          ← 注入任务输入（注意：此时还没有答案）
      ↓
run agent to completion    ← agent 拿到任务描述后自主跑到完成
      ↓
stage hidden reference     ← 此时才注入隐藏答案（防泄漏）
      ↓
grade output               ← evaluate() 确定性打分
      ↓
score + logs + trajectory  ← 收集统一轨迹 + 原始日志 + artifacts
```

**核心设计**：ALE 不逐步"提线"agent，而是只给任务描述，让它**自主跑到底**，然后给最终产物打分。这样保留 agent 自身的 action loop、tools、memory、sub-agents，让风格迥异的 agent 在"工作是否完成"这一条轴上变得可比较。

---

## 三、三种可互换的组件

### 1. Agent Harness（被测系统）

- https://agents-last-exam.org/docs?p=pages/agents.html
- 支持的形态：CLI 原生 harness（Claude Code、Codex CLI、Openclaw 等）
- **关键概念**：ALE 评测的是论文中定义的 **Generalist CUA-agent**——同时具备 **CLI + GUI** 双能力的通用计算机使用 agent
- **CUA MCP Bridge**：把桌面操作（截图、点击、输入、滚动等）作为 MCP tool 暴露给 agent，让纯 CLI harness 也能"长出"GUI 能力

### 2. Environment（Sandbox）

- https://agents-last-exam.org/docs?p=pages/sandbox.html
- GCP: https://agents-last-exam.org/docs?p=pages/google-cloud.html
- 真实 VM，**非简化环境**：完整 Windows/Linux OS + 专业软件 + 真实任务数据
- 两种 harness 接入方式：
  - **In-sandbox**：CLI 直接注入到 VM 内执行
  - **Out-of-sandbox**：harness 在 ALE 进程外执行，通过**双 MCP Bridge**（一个 CLI、一个 GUI）远程驱动 VM
  - 参考实现：`ale_run/agents/ale_claw`（即 ALE-Claw）

### 3. Task

- https://agents-last-exam.org/docs?p=pages/tasks.html
- 每个任务是一个可执行的 `main.py`
- 组成：指令（instruction）+ 输入数据 + **隐藏 reference** + `evaluate()` grader
- **可验证结果**：所有任务都有客观打分，非主观偏好判断
- **经济价值映射**：任务来源映射到 O*NET / SOC 2018（美国联邦职业分类体系）

---

## 四、完整轨迹记录（可审计）

每次 run 都全量记录以下内容：

- https://agents-last-exam.org/docs?p=pages/trajectories.html
- **统一 trajectory schema**：每一步 + tool call + observation 都归一到同一格式
- **agent 原始 logs**：保留 agent 自己的日志
- **evaluation result**：打分结果
- **artifacts**：写过的文件、看过的截图等

→ 任意一次 run 都可以**端到端 replay 与审计**。

---

## 五、任务集分档（Curated Task Lists）

| Tier | 难度 |
|---|---|
| **near-term** | 近期可达 |
| **full-spectrum** | 全谱 |
| **last-exam** | 最难（前沿模型 ~2.6% 通过率） |

另有 **unlicensed track** 与 **Linux-only slice**。

一次完整实验本身就是一个 YAML 文件，把 agent 矩阵 + 环境 + 任务列表 wire 起来，结果推到 GCS bucket。

---

## 六、如何接入自己的 Agent

只需实现一个小的 **deployer**。详见官方文档站点（`agents-last-exam.org/docs`）：

- **Build on ALE → Add an agent**：将自己的 harness 或 CLI 接入 ALE
- **Build on ALE → Implement a deployer**：定义 sandbox 启动逻辑
- **Benchmark your own harness on ALE**：反向用自己的 harness 评测 ALE

### 快速上手

`docs/quickstart.md` 提供一条命令跑通流程：

> boot cloud sandbox → run agent on hello-world task → grade

前置工作：一次性 GCP 账号配置（约 10 分钟，覆盖在 $300 免费额度内）。可以手动配置账号后，把剩余文档交给 coding agent 完成。

---

## 七、与同类框架对比

| 维度 | **ALE** | SWE-bench | Terminal-Bench 2.0 |
|---|---|---|---|
| **执行环境** | 真实 VM（Win/Linux + 专业软件） | Docker 容器（Python repo） | 容器化终端 |
| **Agent 形态** | 自主 agent（CLI + GUI 双修 CUA） | 主要测 patch 生成 | CLI agent |
| **任务类型** | 跨 55 行业真实经济价值工作 | GitHub issue 修复 | 命令行端到端任务 |
| **评分方式** | 隐藏 reference + 确定性 grader | 测试套件通过率 | 任务级 milestone |
| **防作弊机制** | reference 在 agent 完成后才注入 | Verified 子集人工筛选 | 全任务可复现验证 |
| **接入方式** | 实现 deployer | 提交 patch | Harbor 框架 |

**ALE 的差异化定位**：

- 目前**唯一**官方强调 "CLI + GUI 双修 CUA agent" 的开源评测框架
- 任务覆盖 55 个非体力行业（基于 O*NET / SOC 2018）
- 每个任务源自真实专家项目，结果客观可验证
- 采用 rolling benchmark 模式，避免饱和

---

## 八、典型应用场景

1. **前沿 agent 系统评测**：测真实长时程专业工作完成度（最难 tier 通过率仅 ~2.6%）
2. **自研 agent 接入**：通过实现 deployer 把自家 harness 接到 ALE
3. **CI/回归测试**：用 curated task list 做 agent 升级后的回归基线
4. **跨 agent 比较**：统一 trajectory + 客观打分，让不同风格 agent 可比较

---

## Sources

- [rdi-berkeley/agents-last-exam (GitHub)](https://github.com/rdi-berkeley/agents-last-exam)
- [ALE 官方文档](https://agents-last-exam.org/docs)
- [ALE Leaderboard](https://agenthle.org/leaderboard)
- [arXiv: 2606.05405](https://arxiv.org/abs/2606.05405)
- [Hugging Face Papers](https://huggingface.co/papers/2606.05405)
- [Snorkel AI 解读](https://snorkel.ai/agents-last-exam-can-ai-agents-actually-do-real-jobs/)
- [Dawn Song LinkedIn 公告](https://www.linkedin.com/pulse/introducing-agents-last-exam-ale-new-standard-evaluating-dawn-song-dntuc)
