---
name: harness-audit
description: >
  对已存在项目执行全面审计，探测构建系统、模块布局、测试现状、历史债指标，
  并产出推荐 harness.yaml 初始配置。现有项目接入 harness 的第一步。
  当用户提到"接入 harness"、"审计项目"、"现有项目接入"、"harness-audit"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Harness-Audit Skill

> 审计现有项目的当前状态，产出三份报告：当前状态、gap、迁移风险。为 migration-roadmap skill 提供输入。

## 何时使用
- 现有项目首次接入 harness 框架
- 需要评估项目当前状态与 harness 标准的差距
- 为分阶段迁移计划提供基线数据
- 功能流程入口：harness-audit → migration-roadmap → harness-creator/harness-executor

## 前置条件
- project_path 指向的项目目录存在且非空
- 项目包含至少一种构建系统文件（build.gradle / pom.xml / package.json 等）

## 输入
- project_path：被审计的项目根目录路径

## 输出
在 `doc/features/{project}/outputs/harness-audit/` 下生成三份报告：
- `{YYYY-MM-DD}-current-state.md` — 当前状态报告
- `{YYYY-MM-DD}-gap-analysis.md` — 与 harness 标准的差距分析
- `{YYYY-MM-DD}-risk-assessment.md` — 迁移风险评估
- 可选 `{YYYY-MM-DD}-initial-harness.yaml` — 推荐的初始配置片段

## 工作流程

### 步骤 1：验证项目路径
确认 project_path 存在，读取目录结构。

### 步骤 2：探测构建系统
按优先级检查：
1. build.gradle / build.gradle.kts / settings.gradle / settings.gradle.kts → Android/Gradle
2. pom.xml → Maven
3. package.json → Node.js/npm
4. Cargo.toml → Rust
5. go.mod → Go

未识别时打印 warning 并继续通用扫描。

### 步骤 3：扫描目录结构
- 识别源码根目录（src/, app/src/, packages/）
- 识别模块目录（Gradle 多模块的 :module_name）
- 识别测试目录和测试框架（test/, androidTest/, __tests__/）
- 识别配置文件（AGENTS.md、CONSTRAINTS.md、harness.yaml 等 harness 组件）

### 步骤 4：计算历史债指标
- 超大文件清单：扫描所有源码文件，按行数排序，取 top 20
- 疑似循环依赖：对 Gradle 项目，尝试解析模块依赖图，报告成环的模块对
- God 文件标注：按超大文件清单，标注超过 800 行的文件

### 步骤 5：调用代码质量扫描
运行 `python scripts/lint_quality.py --baseline-init` 初始化历史债基线。

### 步骤 6：调用架构依赖扫描
运行 `python scripts/lint_deps.py` 检查架构层级依赖违规。

### 步骤 7：生成三份报告并汇总 gap 评分

#### 当前状态报告 (current-state.md)
```
# Current State Report: {project_name}

## 项目概况
- 构建系统：Gradle (Android)
- 源码模块：[:app, :lib_core, :lib_router, ...]
- 测试现状：暂无测试框架检测到

## 目录结构
- 源码根目录：src/, app/src/
- 模块清单：列出检测到的所有模块

## 测试基础设施
- 测试框架：检测到的测试框架
- 测试覆盖率：如有数据则填入

## 历史债摘要
### 超大文件 (Top 20)
| 文件 | 行数 | 风险 |
|------|------|------|
| src/.../God.java | 1544 | HIGH |

### 疑似循环依赖
- lib_router ↔ lib_resource
```

#### Gap 分析报告 (gap-analysis.md)
```
# Gap Analysis: {project_name}

## Harness 组件覆盖

| 组件 | 状态 | 说明 |
|------|------|------|
| AGENTS.md | 缺失 |  |
| CONSTRAINTS.md | 缺失 |  |
| BLACKBOARD.md | 缺失 |  |
| harness/ | 缺失 |  |
| scripts/ | 缺失 |  |
| .agents/skills/ | 缺失 |  |

## 架构层级覆盖
| 层级 | 状态 | 检测到的路径 |
|------|------|------|
| api | 缺失 |  |
| service | 缺失 |  |
| repository | 缺失 |  |

## Gap 评分
总分：{X}/100
```

#### 风险评估报告 (risk-assessment.md)
```
# Risk Assessment: {project_name}

## 高风险项
| 风险 | 影响 | 建议 |
|------|------|------|
| 循环依赖 | 架构腐败 | 优先解环 |

## 中风险项
| 风险 | 影响 | 建议 |
|------|------|------|
| 超大文件 | 可维护性差 | 重构或拆分 |

## 低风险项
| 风险 | 影响 | 建议 |
|------|------|------|
| 缺少测试 | 回归风险 | 逐步补充 |
```

### 步骤 8：生成推荐初始配置
基于审计结果生成 `harness.yaml` 初始配置片段：

```yaml
# 推荐的初始配置片段
exclude_paths:
  quality:
    exclude_paths:
      - "**/build/**"
      - "**/.gradle/**"
      - "**/generated/**"
  architecture:
    exclude_paths:
      - "**/build/**"
      - "**/.gradle/**"
      - "**/generated/**"
    generated_code_paths:
      - "build/generated/"
      - "**/generated/"
      - "R.java"
      - "BuildConfig.java"

baseline_mode:
  quality:
    baseline_mode: "baseline"  # 豁免历史债，渐进改造
```

## 前置条件检查

| 条件 | 不满足时 |
|------|---------|
| project_path 存在 | 报错：Project path not found |
| 包含已知构建系统 | 打印 warning，继续通用扫描模式 |
| 可读目录 | 报错：Permission denied |

## 产物格式规范

### 报告文件命名
- `harness-audit/{YYYY-MM-DD}-current-state.md`
- `harness-audit/{YYYY-MM-DD}-gap-analysis.md`
- `harness-audit/{YYYY-MM-DD}-risk-assessment.md`
- 可选 `harness-audit/{YYYY-MM-DD}-initial-harness.yaml`

### 表格格式
使用 markdown 表格，列对齐，包含：文件/风险项、状态/行数/影响、说明/建议。

### JSON 结构（供下游 skill 消费）
```json
{
  "project_name": "{name}",
  "build_system": "{detected}",
  "modules": ["module1", "module2"],
  "gap_score": 0-100,
  "high_risks": ["risk1", "risk2"],
  "recommended_config": { ... }
}
```

## 触发条件
- 用户提到"接入 harness"
- 用户提到"审计项目"
- 用户提到"现有项目接入"
- 用户提到"harness-audit"
- migration-roadmap skill 请求审计输出

## 不触发条件
- 项目已有完整 harness 基础设施（建议直接使用 harness-executor）
- 项目是全新空项目（建议使用 project-init）
- 已有审计报告且用户未要求重新审计

## 错误处理
- 项目路径不存在 → 报错并终止
- 构建系统无法识别 → 继续通用扫描，打印 warning
- 目录无读取权限 → 报错并终止
- 脚本执行失败 → 记录错误，继续生成报告（标注失败步骤）
- 超大文件扫描超时 → 跳过超时文件，在报告中标注

## 完成标准
- [ ] 项目路径验证通过
- [ ] 构建系统已识别
- [ ] 目录结构已扫描
- [ ] 历史债指标已计算
- [ ] 三份报告已生成
- [ ] Gap 评分已汇总
- [ ] 推荐配置已生成（如需要）
- [ ] Trace 条目已写入
