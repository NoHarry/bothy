---
name: migration-roadmap
description: >
  基于 harness-audit 产出，生成渐进式改造路线图。
  将历史债任务按 T 恤尺寸（S/M/L）分级，输出 3-phase 清单。
  当用户提到"迁移"、"roadmap"、"migration-roadmap"、"改造路线图"时激活此 Skill。
triggers:
  - "迁移"
  - "roadmap"
  - "migration-roadmap"
  - "改造路线图"
  - "生成改造路线图"
preconditions:
  - harness-audit 产出存在（doc/features/{project}/outputs/harness-audit/ 目录下有三份报告）
  - 三份报告包括：audit-report.md、constraint-gaps.md、quality-gaps.md
inputs:
  - doc/features/{project}/outputs/harness-audit/audit-report.md
  - doc/features/{project}/outputs/harness-audit/constraint-gaps.md
  - doc/features/{project}/outputs/harness-audit/quality-gaps.md
outputs:
  - doc/features/{project}/outputs/migration-roadmap.md
---

# Migration-Roadmap Skill

> 消费 harness-audit 产出，生成分阶段迁移路线图。

## 何时使用

- harness-audit 完成后，需要将发现的问题转化为可执行的改造计划
- 需将历史债任务按优先级和复杂度分级
- 需生成 3-phase 渐进式改造路线图供 harness-executor 执行

## 前置条件

- doc/features/{project}/outputs/harness-audit/ 目录下已生成三份报告：
  - audit-report.md（总体审计报告）
  - constraint-gaps.md（约束缺失清单）
  - quality-gaps.md（质量缺口清单）

## T 恤尺寸定义

| 尺寸 | 含义 | 典型工期 | 适用场景 |
|------|------|---------|---------|
| S | 短时间、低风险，可独立完成 | < 1 周 | 配置填写、文件移动、简单重命名 |
| M | 中等复杂度，需关注测试 | 1-3 周 | 单一模块重构、小文件拆分 |
| L | 高风险、跨模块、需安全网 | > 3 周 | God 文件拆分、循环依赖打破 |

## 工作流程

### 步骤 1: 读取 audit 三份报告

读取以下文件：
- doc/features/{project}/outputs/harness-audit/audit-report.md
- doc/features/{project}/outputs/harness-audit/constraint-gaps.md
- doc/features/{project}/outputs/harness-audit/quality-gaps.md

解析每份报告中的 gap 条目，提取：
- gap 描述
- 所属类别（constraint / quality）
- 文件路径
- 预估工作量

### 步骤 2: 按 gap 类型分类到 3 个 phase

根据 gap 的性质和优先级，将其归类到三个阶段：

#### Phase 1: 快速稳定（Baseline 锁现状）

目标：接入 harness 基础设施，锁定当前状态

典型任务（S 任务为主）：
- 复制模板到项目根目录
- 基于 audit-report 填写 harness.yaml（exclude_paths, baseline_mode, platforms.android.gradle_modules）
- 运行 lint_quality.py --baseline-init 建立基线
- 运行 validate.py 确认退出码 0
- 首次 commit harness 配置

#### Phase 2: 架构清理（Build Safety Net）

目标：建立安全网易守，防止退化

典型任务（M 任务为主）：
- 关键路径手工冒烟测试清单
- 基础 CI 配置（lint → validate on PR）
- 表征性测试（characterization tests）覆盖核心业务逻辑
- 建立基线监控（baseline 违规数不增加）

#### Phase 3: 持续优化（Progressive Refactor）

目标：渐进式解决历史债

典型任务（L 任务为主）：
- God 文件拆分（按业务边界拆为 300 行以内文件）
- 打破循环依赖（lib_router ↔ lib_resource）
- 清理 generated_code_paths 中的历史违规
- 切换到 strict 模式

### 步骤 3: 识别依赖关系

某些 Phase 2/3 任务依赖 Phase 1 的完成：
- Phase 2 任务需 Phase 1 baseline 建立后才能开始
- Phase 3 任务需 Phase 2 安全网就绪后才能执行
- L 任务（God 文件拆分）需所有 M 任务完成

### 步骤 4: 生成风险清单

对每个任务评估：
- 技术风险（HIGH / MEDIUM / LOW）
- 回滚难度
- 影响范围

### 步骤 5: 输出路线图

将路线图写入 doc/features/{project}/outputs/migration-roadmap.md

## 产物格式

```markdown
# Migration Roadmap: {project_name}

生成时间: {date}

## Phase 1: 接入完成（Quick Stabilization）

| 任务 | 尺寸 | 前置条件 | 风险 | 验收标准 |
|------|------|---------|------|---------|
| 填写 harness.yaml | S | audit-report | LOW | harness.yaml 存在且配置正确 |
| lint_quality.py --baseline-init | S | harness.yaml | LOW | 基线违规数已记录 |
| validate.py 首次通过 | S | baseline init | LOW | 验证退出码为 0 |
| 首次 commit harness 配置 | S | validate 通过 | LOW | git commit 已创建 |

**验收标准**：Phase 1 全部任务完成后，baseline 已建立，项目进入受控状态。

## Phase 2: 安全网建立（Safety Net）

| 任务 | 尺寸 | 前置条件 | 风险 | 验收标准 |
|------|------|---------|------|---------|
| 配置 GitHub Actions CI | M | Phase 1 完成 | MEDIUM | CI pipeline 存在且触发正常 |
| 关键冒烟测试清单 | M | Phase 1 完成 | MEDIUM | 清单包含核心路径测试 |
| 表征性测试覆盖 | M | Phase 1 完成 | MEDIUM | 覆盖率 > 60% |
| 建立基线监控 | M | Phase 1 完成 | MEDIUM | 违规数仪表盘可查看 |

**验收标准**：Phase 2 全部任务完成后，项目具备防退化机制。

## Phase 3: 渐进重构（Progressive Refactor）

| 任务 | 尺寸 | 前置条件 | 风险 | 验收标准 |
|------|------|---------|------|---------|
| 拆分 ProtocolRequestId.java (1544 LOC) | L | Phase 2 完成 | HIGH | 文件 < 300 行，无新增违规 |
| 拆分 MusicClientService.java (1196 LOC) | L | Phase 2 完成 | HIGH | 文件 < 300 行，无新增违规 |
| 打破 lib_router ↔ lib_resource 循环 | L | Phase 2 完成 | HIGH | 循环依赖已消除 |
| 清理 generated_code_paths 历史违规 | M | Phase 2 完成 | MEDIUM | 历史违规已清零 |
| 切换到 strict 模式 | M | Phase 3 所有 L 完成 | MEDIUM | validate.py 退出码为 0 |

**验收标准**：Phase 3 全部任务完成后，项目进入 strict 模式，无历史债残留。

## 风险总览

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| God 文件拆分失败 | HIGH | 先建测试安全网，小步迭代 |
| 循环依赖打破后引入新 bug | HIGH | 表征性测试覆盖，CI 强制通过 |
| 基线建立后发现更多违规 | MEDIUM | 定期评审基线，及时调整 |
```

## 与 harness-executor 的衔接

roadmap 中每个任务可作为独立任务直接给 harness-executor 执行：

```
roadmap task → harness-executor → 已验证的代码变更
```

harness-executor 会：
1. 读取任务描述和验收标准
2. 预验证操作
3. 执行变更
4. 运行验证流程
5. 记录追踪条目

## 完成标准

- [ ] 三份 audit 报告已读取并解析
- [ ] 所有 gap 已归类到 3 个 phase
- [ ] 每个任务包含尺寸、前置条件、风险、验收标准
- [ ] 依赖关系已识别并在表格中体现
- [ ] 风险清单已生成
- [ ] 路线图已写入 doc/features/{project}/outputs/migration-roadmap.md

## 错误处理

- audit 报告缺失 → 提示先运行 harness-audit skill
- gap 无法归类 → 放入最保守的 phase（Phase 3）
- 依赖关系不明确 → 标记为"依赖 Phase 1 完成"，保守处理
