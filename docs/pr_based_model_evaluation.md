# 基于已有 GitHub PR 的模型修复评测流程

本文档定义一种评测流程：**已有 GitHub PR 只作为问题真实存在和参考验证的证据，不作为模型输入答案**。目标是让模型在干净的 base code 上独立生成修复 patch，并验证该 patch 是否真正修复问题。

## 目标

给定一个 GitHub PR 或关联 issue，产出一次可复现的模型修复评测：

1. 从 PR/issue 提取问题、base commit、参考修复和验证线索。
2. 构造一个评测 instance。
3. 让模型只看到问题描述和 base code，生成修复 patch。
4. 用测试脚本验证模型 patch。
5. 输出是否修复成功、失败原因、日志和最终 patch。

## 核心原则

- **PR 是证据，不是提示词答案**：PR diff 可以用于构造 gold patch、测试和人工对照，但不能泄露给被测模型。
- **模型必须从 base commit 开始修复**：评测代码库应 checkout 到 PR 合入前的基线提交。
- **验证要独立于模型输出**：测试脚本应能判断模型 patch 是否解决问题，而不是只比较 diff 文本。
- **所有产物可追溯**：保留输入 issue/PR、base commit、模型 patch、测试日志、解析结果和最终结论。

## 输入

最少输入：

```text
GitHub PR URL
```

推荐输入：

```text
GitHub PR URL
关联 issue URL（如果 PR body 未明确关联）
目标仓库默认分支或 base branch
被测模型/agent 名称
是否允许使用 PR 中新增/修改的测试作为验证测试
是否需要严格避免模型看到 PR diff（默认：是）
```

## 输出

一次完整评测应至少输出：

```text
artifacts/<instance_id>/problem_statement.md
artifacts/<instance_id>/gold.patch
artifacts/<instance_id>/model.patch
artifacts/<instance_id>/run.log
artifacts/<instance_id>/test_output.json
artifacts/<instance_id>/evaluation_report.md
```

如果接入当前 SWE-bench Pro harness，还需要：

```text
raw_sample.jsonl 或 raw_sample.csv
patches.json
run_scripts/<instance_id>/run_script.sh
run_scripts/<instance_id>/parser.py
```

## 端到端阶段

### 1. 采集 PR/issue 证据

从 GitHub PR 页面、API 或本地 git 获取：

- PR 标题和描述
- 关联 issue 描述
- base branch 和 head branch
- base commit：PR 分支从目标分支分叉时的提交，或 PR 合入前目标分支的基线提交
- PR diff：保存为 `gold.patch`，仅用于参考和验证，不给模型
- PR 中新增/修改的测试文件：作为候选验证测试

产物：

```text
problem_statement.md
gold.patch
metadata.json
```

建议 `metadata.json` 结构：

```json
{
  "instance_id": "instance_owner__repo-pr123",
  "repo_url": "https://github.com/owner/repo",
  "pr_url": "https://github.com/owner/repo/pull/123",
  "issue_url": "https://github.com/owner/repo/issues/456",
  "base_commit": "<sha>",
  "head_commit": "<sha>",
  "base_branch": "main"
}
```

### 2. 构造问题描述

模型可见的 `problem_statement.md` 应只包含解决问题所需信息：

- 用户可见的问题现象
- 期望行为
- 复现步骤（如果 issue/PR 提供）
- 错误日志或失败测试名称（如果可公开给模型）
- 约束条件和兼容性要求

禁止包含：

- PR diff 里的实现细节
- 明确指出“修改某文件某函数为某代码”的答案式描述
- commit patch 内容
- review comment 中直接泄露修复方案的片段

### 3. 准备 base repo

将目标仓库 checkout 到 `base_commit`：

```bash
git clone https://github.com/owner/repo.git worktrees/<instance_id>
cd worktrees/<instance_id>
git checkout <base_commit>
```

此时仓库必须处于未修复状态。若 PR 中包含测试，可先确认测试在 base commit 上失败，证明问题可复现。

### 4. 设计验证测试

验证测试来源优先级：

1. PR 新增/修改的测试。
2. 关联 issue 可复现步骤改写成自动化测试。
3. 项目已有相关测试子集。
4. 人工补充的最小回归测试。

测试脚本应满足：

- 在 base commit 上失败或暴露问题。
- 在 PR gold patch 上通过。
- 对模型 patch 只判断行为，不要求 diff 一致。

当前项目接入点：

```text
run_scripts/<instance_id>/run_script.sh
run_scripts/<instance_id>/parser.py
```

`run_script.sh` 负责运行测试；`parser.py` 负责把 stdout/stderr 解析成统一 JSON。

### 5. 运行模型生成 patch

模型输入只包含：

- base repo
- `problem_statement.md`
- 必要环境说明

模型输出保存为：

```text
model.patch
```

如果使用 SWE-agent 或 mini-swe-agent，推荐保留其原始轨迹和 `.pred` 文件，再用当前仓库的汇总脚本转成评测 JSON：

```bash
python helper_code/gather_patches.py \
  --directory <agent_output_dir> \
  --prefix <model_name> \
  --output patches.json
```

### 6. 应用并评测模型 patch

评测逻辑：

1. checkout 到 `base_commit`
2. 应用模型 patch
3. 安装/准备依赖
4. 运行 `run_script.sh`
5. 用 `parser.py` 生成测试 JSON
6. 判断目标测试是否通过

如果接入 `swe_bench_pro_eval.py`，命令形态为：

```bash
python swe_bench_pro_eval.py \
  --raw_sample_path <raw_sample.jsonl> \
  --patch_path patches.json \
  --output_dir <eval_output> \
  --scripts_dir run_scripts \
  --dockerhub_username <dockerhub_user> \
  --use_local_docker
```

如果目标仓库没有现成 Docker image，可以先用普通本地 runner 验证，再决定是否补齐 Docker 化 instance。

### 7. 判定结果

建议判定为以下状态之一：

| 状态 | 含义 |
| --- | --- |
| `resolved` | patch 可应用，目标失败测试通过，回归测试通过 |
| `partial` | patch 可应用，部分目标测试通过，但仍有失败 |
| `compile_error` | patch 应用后构建、类型检查或语法失败 |
| `patch_apply_failed` | patch 无法应用到 base commit |
| `test_infra_failed` | 测试环境、依赖、Docker 或 parser 失败，无法判断修复质量 |
| `invalid` | patch 与问题无关或破坏关键行为 |

最终报告应包含：

```text
- PR/issue 链接
- base commit
- 被测模型/agent
- 是否使用 PR 测试
- 是否向模型隐藏 PR diff
- patch 应用结果
- 测试命令
- 通过/失败测试列表
- 结论：resolved / partial / ...
- 与 gold patch 的高层差异（可选，评测后分析）
```

## 推荐目录结构

```text
artifacts/pr_eval/<instance_id>/
  metadata.json
  problem_statement.md
  gold.patch
  model.patch
  patches.json
  eval/
    eval_results.json
    <instance_id>/
      <prefix>_patch.diff
      <prefix>_stdout.log
      <prefix>_stderr.log
      <prefix>_output.json
      <prefix>_entryscript.sh
  evaluation_report.md
```

## 当前项目脚本对应关系

| 阶段 | 当前项目文件 |
| --- | --- |
| 从 dataset 生成 SWE-agent instances | `helper_code/generate_sweagent_instances.py` |
| 汇总模型 `.pred` | `helper_code/gather_patches.py` |
| 提取 gold patches | `helper_code/extract_gold_patches.py` |
| 单实例 SWE-agent smoke flow | `helper_code/run_sweagent_single_e2e.py` |
| 执行 Docker/Modal 评测 | `swe_bench_pro_eval.py` |
| 每个实例的测试入口 | `run_scripts/<instance_id>/run_script.sh` |
| 每个实例的测试解析器 | `run_scripts/<instance_id>/parser.py` |

## 防污染检查清单

在运行模型前确认：

- [ ] 模型提示词未包含 `gold.patch`。
- [ ] 模型上下文未打开 PR diff。
- [ ] problem statement 没有答案式代码描述。
- [ ] base repo checkout 到未修复 commit。
- [ ] 验证测试在 gold patch 上可通过。
- [ ] 模型 patch 和 gold patch 分别保存，路径清晰区分。

## 最小可执行流程

给定一个 PR URL 后，推荐先跑最小闭环：

```text
1. 获取 PR 元信息和 diff。
2. 写 problem_statement.md，隐藏 diff。
3. checkout base commit。
4. 从 PR 中提取或补充一个最小失败测试。
5. 运行模型生成 model.patch。
6. 在 base commit 上应用 model.patch。
7. 跑测试。
8. 写 evaluation_report.md。
```

若最小闭环稳定，再补齐当前 SWE-bench Pro harness 所需的 `raw_sample`、`run_scripts`、Docker image 和 `patches.json`。
