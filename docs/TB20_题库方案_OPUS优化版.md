# TB2.0 的核心初衷可以概括为：

在容器化终端环境中，评估 agent 独立完成真实、端到端、可验证任务的能力。
任务应当 solvable、realistic、well-specified，并通过测试脚本可靠判分。

官方/Harbor 文档也强调任务由 instruction、container environment、test script 组成，使用 Harbor 在容器中运行 agent。参考：

- https://harborframework.com/docs/running-tbench
- https://harborframework.com/docs/tasks
- https://github.com/harbor-framework/terminal-bench


# 生产体系分工

整个题库生产分两个独立阶段，对应两个 skill：

| 阶段 | Skill | 边界 |
|------|-------|------|
| 内容生产 | `tb20-dataset-production` | 从 brief 到 `instruction.md` + `test-generation-brief.md`，不跑 Harbor |
| 执行交付 | `tb20-batch-execution-delivery` | 从已有 source 到 agent log 采集 + 交付打包，不创建任务 |

两个阶段严格串行，不混用。执行阶段的输入必须已通过 oracle 验证。


# “海量出题” 拆成两个问题：

1. 选大模型擅长、稳定、可验证的场景
2. 用系统化组合方法做”学科点碰撞”，批量生成不重复题

核心不是让模型随便发散，而是给它一个”题目生成空间”。

## 一、优先选择大模型擅长的场景

大模型最适合出这几类题：

1. 结构化文件处理
   例如 CSV、JSON、YAML、TOML、XML、INI、日志、manifest、lockfile、配置文件、报表。
2. 格式/协议/规范解析
   例如 TZif、PNG chunk、ZIP central directory、HTTP cache header、DNS zone file、Prometheus exposition format、Git patch、SQL schema migration。
3. 多文件一致性校验
   例如 manifest 对实际文件、配置对日志、schema 对数据、索引对内容、checksum 对包、依赖图对 lockfile。
4. 数据修复和取证
   例如损坏归档、缺失字段恢复、重复项识别、时间线重建、权限异常定位、WAL/backup/metadata 对账。
5. 可程序化验证的工程任务
   例如写 parser、生成报告、转换格式、分类边界情况、修复 CLI 行为、实现小型调度/排序/匹配算法。

不适合优先选：

- 纯主观问答
- 需要大量外部知识且难验证
- UI 审美类
- 依赖实时网络状态
- 需要复杂系统服务但没有稳定 fixture
- 只靠“写一段说明”无法自动判分的题

## 二、选题公式

一个高产场景通常可以写成：

稳定来源 + 文件/系统状态 + 明确任务 + 可验证输出 + 边界条件

例如：

RFC 8536 + TZif 文件 + 解析并分类 DST gap/fold + JSON/CSV 输出 + corrupt/alias/post-2038 边界

或者：

OCI image spec + manifest/layers + 校验镜像元数据一致性 + report.json + missing blob/duplicate digest/mediaType mismatch

## 三、学科点无限碰撞的方法

不要让模型直接“想 1000 个题”。应该先建一个题目坐标系。

可以用 5 个轴做组合：

领域轴：文件格式 / 网络协议 / 数据库 / 系统运维 / 安全 / 科学计算 / 数据处理
对象轴：日志 / manifest / 二进制文件 / 配置 / 时间序列 / 图 / 表格 / 归档
操作轴：解析 / 校验 / 修复 / 转换 / 对账 / 分类 / 压缩 / 聚合 / 调度
约束轴：损坏 / 缺失 / 重复 / 边界时间 / 大文件 / 排序规则 / 精度容差 / 兼容版本
输出轴：JSON / CSV / patch / report / generated files / CLI behavior

然后碰撞：

文件格式 × 损坏恢复 × manifest 对账 × JSON report
网络协议 × 边界 header × 缓存判定 × CSV report
数据库 × WAL/backup × 时间线恢复 × SQL/JSON
系统运维 × unit files × dependency graph × failure report
科学计算 × tolerance × numerical validation × summary JSON

这样模型不是凭空发散，而是在组合空间里填空。

## 四、批量生产流程

标准 SOP（严格串行，不跳步）：

```
1.  source catalog        — 确认规范来源、license、可复现性
2.  problem card          — 写明任务目标、输入状态、期望输出、边界条件
3.  difficulty expansion  — 三档设计为能力梯度，不是同一任务简单放大
4.  instruction.md        — exact output 要求必须写进 instruction（格式、单位、字段名）
5.  fixture generator     — 脚本生成 seed 数据，不手写 fixture
6.  oracle solution       — solve.sh 先手写通过
7.  verifier              — test.sh + test_outputs.py，只检查 instruction 声明的内容
8.  oracle run            — docker build + docker run 本地验证，三档 reward=1 才继续
9.  harbor run            — claude-code + opus 4.7，--require-no-trial-exceptions --require-reward 1
10. agent log collect     — collect → package → audit 全通过
11. failure taxonomy      — 失败标注原因（说明不清/verifier 过拟合/任务太难/环境失败/模型边界）
12. 入库/package          — 只打包 reward=1 的版本
```

每题必须过两个质量门：

```
oracle reward = 1          （题目本身可解）
target model reward = 1    （对目标模型有效，agent log 真实采集）
```

梯度判读：

| 结果 | 含义 | 处理 |
|------|------|------|
| easy/medium 过，hard 不过 | 好的难度梯度样本 | 微调 verifier 或 instruction，重跑 hard |
| 三档全过 | 题目偏易 | 加边界条件升级 hard |
| 三档全不过 | instruction 不清 / 环境问题 / 任务过硬 | 回到步骤 4 修改，不是放松 verifier |

## 五、Verifier 设计约束（防止过拟合）

从 TZif hard 失败复盘中总结：verifier 最常见的失败模式是检查了 instruction 未声明的细节。

规则：

1. **只检查 instruction 声明的内容**。未明确要求的精确字符串、单位、offset 格式，不得硬断言。
2. **exact output 必须写进 instruction**。如果 verifier 要检查某字段的精确格式，instruction 必须先写明。
3. **等价格式接受**。例如 UTC offset 同时接受秒数 `-18000` 和 ISO 格式 `-05:00`，除非 instruction 明确指定其中一种。
4. **语义检查优于字符串匹配**。`reason` 字段检查”包含关键语义词”比检查精确字符串更合理。
5. **核心结构不放松**。字段名、schema、文件路径、排序、pass/fail 分类——这些是任务核心，必须严格检查。

Verifier 分层：

```
Layer 1 - 文件存在性：输出文件存在，格式可解析（JSON valid / CSV 列数对）
Layer 2 - schema 检查：required 字段都存在，类型正确
Layer 3 - 语义检查：instruction 声明的核心逻辑正确
Layer 4 - 边界检查：instruction 声明的边界条件正确处理
```

禁止在 Layer 3/4 检查 instruction 未声明的格式细节。

## 六、快速累积题库的关键机制

要快，必须模板化：

1. **场景模板**
   例如”格式解析类””bundle audit 类””配置对账类””时间线恢复类”。同一场景模板可以复用 Dockerfile、verifier 骨架，只换 fixture。

2. **fixture 生成器**
   用脚本生成不同 seed 的数据，不手写 fixture。fixture 生成器本身入库，可重复产出变体题。

3. **verifier 模板**
   每类任务都有通用测试骨架（JSON schema check + semantic check + boundary check）。

4. **难度升阶模板**
   - easy：单文件、标准输入、少边界、单一输出格式
   - medium：多文件或多版本、边界时间/兼容性版本、复合输出
   - hard：损坏/缺失/重复输入、冲突规则、组合推理、多输出文件

5. **失败分类入库**
   每次目标模型失败，标注失败原因后入库，而不是丢弃：

   | 失败原因 | 处理方向 |
   |---------|---------|
   | instruction 不清 | 修改 instruction，重跑 |
   | verifier 过拟合 | 放松非核心断言，重跑 |
   | 任务太难 | 降为 hard+，标记为”超纲样本” |
   | 环境失败 | 修 Dockerfile，重跑 |
   | 模型能力边界 | 保留为高价值 hard 样本，标注模型 |

## 七、推荐优先扩展的高产方向

适合作为海量题库母题：

1. binary formats
   PNG, WAV, ZIP, TAR, SQLite page, TZif, ELF subset

2. config and manifests
   systemd unit, Kubernetes YAML, OCI manifest, package lock, GitHub Actions workflow

3. time and logs
   rotation, timezone, clock skew, incident timeline, audit trails

4. data validation
   schema drift, CSV normalization, JSONL aggregation, duplicate detection

5. security defensives
   SBOM audit, weak config detection, CVE range matching, secret redaction

6. graph/dependency
   topological order, cycle detection, dependency impact, rollout planning

7. scientific/numeric
   tolerance checks, matrix metadata, signal windows, unit conversion

## 八、执行阶段工程细节

从流程复盘中沉淀的执行阶段必知规则：

**模型配置**
- Harbor 容器不自动继承宿主 Claude 配置，必须用 `--claude-settings-from-host ~/.claude/settings.json` 显式注入。
- 使用完整模型名（如 `claude-opus-4-7`），不用别名（如 `opus4.7`），避免 provider endpoint 不认识别名。
- 如果宿主 `ANTHROPIC_BASE_URL` 指向第三方兼容 endpoint（如 DeepSeek），执行前必须确认注入的是目标 provider 的 env。

**Harbor 执行**
- 本地 Dockerfile 任务必须加 `--force-build`，否则 Harbor 尝试 pull 不存在的 registry tag 导致失败。
- `harbor` exit code `0` 不代表 trial 成功，必须读 `result.json` 中的 `exception_info` 和 `verifier_result`。
- 调试阶段加 `--no-delete`，保留 job/trial 目录便于查 agent log。
- zsh 中变量后跟 `:` 会被解析为参数修饰符，必须写 `”${name}:${tag}”` 而不是 `”$name:$tag”`。

**Agent log 采集**
- Harbor 当前输出路径：`<jobs-dir>/<job-name>/<trial-name>/agent/trajectory.json`，需规范化到 `agent-logs/trajectory.json`。
- 如果 Harbor 没有输出 `ctrf.json`，执行脚本从真实 `test-stdout.txt` + `reward.txt` 生成最小 CTRF wrapper，但不能把 `reward=0` 伪造成成功。
- `claude-code.txt` 必须采集，不能只采集 `trajectory.json`。

**质量门控命令**
```bash
python tb20_execute.py run \
  --agent claude-code \
  --model claude-opus-4-7 \
  --claude-settings-from-host ~/.claude/settings.json \
  --force-build --no-delete \
  --require-no-trial-exceptions \
  --require-reward 1
```

all-green delivery 必须带 `--require-no-trial-exceptions --require-reward 1`，只有用户明确要求收集失败证据时才省略。

## 九、最终策略

不要追求”大模型随机生成很多题”。要追求：

```
有限场景模板 × 多学科组合 × 自动 fixture × 自动 verifier × 模型实跑筛选
```

题库增长路径：

1. 每个母题方向（如 binary formats）先做一个完整的三难度样例，跑通全流程。
2. 提取该方向的场景模板、fixture 生成器、verifier 骨架，入库。
3. 用模板批量生成同方向变体题（不同 seed、不同边界条件）。
4. 每个变体自动过 oracle → Harbor 两道门，失败的标注原因入库。
5. 横向扩展到其他母题方向，复用已有模板中的通用骨架。

这样题库增长会快，而且质量可控。