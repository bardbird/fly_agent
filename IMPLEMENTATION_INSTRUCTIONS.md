# Fly Agent平台实施指南

## Worktree信息

- **位置**: `/Users/liuyifei/Liu/gitee/fly_agent/.worktrees/fly-agent-platform`
- **分支**: `feature/fly-agent-platform`
- **实施计划**: `docs/plans/2026-02-05-fly-agent-platform.md`

## 如何开始实施

### 在新会话中执行

1. **打开新终端并进入worktree目录**:
   ```bash
   cd /Users/liuyifei/Liu/gitee/fly_agent/.worktrees/fly-agent-platform
   ```

2. **启动Claude Code新会话**:
   ```bash
   claude
   ```

3. **在Claude Code中调用executing-plans技能**:
   ```
   /executing-plans docs/plans/2026-02-05-fly-agent-platform.md
   ```

   或者使用Skill工具:
   ```
   使用superpowers:executing-plans技能来执行实施计划
   计划文档路径: docs/plans/2026-02-05-fly-agent-platform.md
   ```

## 实施计划概览

该计划包含9个主要任务：

1. **Task 1**: 创建Maven父POM（版本管理）
2. **Task 2**: 创建Common模块（公共组件）
3. **Task 3**: 创建DAO模块（数据访问层）
4. **Task 4**: 创建Service模块（业务服务层）
5. **Task 5**: 创建API模块（REST接口）
6. **Task 6**: 创建Server模块（启动模块）
7. **Task 7**: 创建Skill文件系统（内置Skills）
8. **Task 8**: 更新README文档
9. **Task 9**: 最终构建验证

每个任务都包含详细的步骤，包括：
- 完整的代码
- 编译验证命令
- Git提交命令

## 环境要求

**必需**:
- ✅ JDK 17+ （当前需要手动升级）
- ✅ Maven 3.8+
- ✅ MySQL 8.0+
- ✅ Redis 6.0+
- ✅ 智谱AI API Key

**注意**: 当前系统JDK版本为1.8，需要升级到JDK 17+才能开始实施。

## 实施模式

使用**executing-plans**技能会：
- 逐任务执行
- 每个任务包含多个步骤
- 自动编译验证
- 自动提交代码
- 设置检查点

## 完成后

实施完成后，使用**finishing-a-development-branch**技能来：
1. 运行完整测试套件
2. 代码审查
3. 合并回主分支
4. 清理worktree

## 实施检查清单

- [ ] JDK 17已安装并配置
- [ ] Maven 3.8+已安装
- [ ] MySQL数据库已创建
- [� Redis已启动
- [ ] 智谱AI API Key已配置
- [ ] 进入worktree目录
- [ ] 启动新Claude Code会话
- [ ] 调用executing-plans技能

---

**准备好后，在新会话中执行**:
```
请使用superpowers:executing-plans技能来执行docs/plans/2026-02-05-fly-agent-platform.md中的实施计划
```
