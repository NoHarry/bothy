---
name: prd-to-design
description: 从 prd-review 产出的功能规格直接生成设计文档，适用于无参考代码可借鉴的新项目。当用户提到"从需求生成设计"、"prd to design"、"新项目设计文档"、"无参考代码时生成设计"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: PRD to Design

> 从 prd-review 产出的功能规格直接生成设计文档。在新项目无参考代码可借鉴时使用，作为 ref-to-design 的平行替代。

## 何时使用
- 全新项目，没有开源参考或类似实现可分析
- PRD 评审已完成，已有 functional-spec.md
- 需要不依赖参考代码的设计起点
- 与 ref-to-design 互补（有参考代码用 ref-to-design，无参考代码用本 skill）

## 前置条件
- `doc/features/{feature-name}/outputs/functional-spec.md` 存在（由 prd-review 产出）
- `doc/features/{feature-name}/outputs/product-spec-review.md` 存在（可选，补充产品语境）
- 设计落点已确定：默认 `design-area={feature-name}`；目标项目可通过 overlay/CONSTRAINTS/WORKFLOW 改为 domain、module 或 bounded context 名称
- 目标项目已搭建 harness 基础设施
- CONSTRAINTS.md 和 AGENTS.md 可读

## 工作流程

### Runtime handoff（默认）

如果存在 `scripts/agent_runtime.py`，先运行 `python scripts/agent_runtime.py next --json`。若存在匹配当前 feature 的 design/planning run，先 `resume --run-id "{run id}" --json` 并从 workpad/handoff 继续；否则创建轻量设计计划 run：

```bash
python scripts/agent_runtime.py start --objective "prd-to-design {feature-name}" --task-type plan --role designer --json
```

### 步骤 1: 校验输入
- 确认 `doc/features/{feature-name}/outputs/functional-spec.md` 存在且包含完整 SPEC 条目
- 确认每个 SPEC 条目包含：模块、状态、类型、输入模式、处理步骤、输出/响应、依赖、验收标准
- 若 functional-spec.md 缺失或不完整，停止并提示回到 prd-review 补齐，不要猜测
- 确定 `design-area`：优先使用用户指定值、feature goal contract、WORKFLOW/CONSTRAINTS 或项目 overlay 中的 domain/module；否则使用 `{feature-name}`

### 步骤 2: 读取必需参考
读取以下核心文件：
- CONSTRAINTS.md — 架构方向、命名规范、技能边界
- AGENTS.md — 项目领域知识、关键目录
- WORKFLOW.md — 当前任务类型、默认 skill、validation profile 和人工确认点
- `doc/features/{feature-name}/outputs/functional-spec.md` — 核心输入
- `doc/features/{feature-name}/outputs/product-spec-review.md` — 补充产品语境（可选）
- `doc/features/{feature-name}/outputs/review-conclusion.md` — 范围和风险边界
- `harness/memory/domain-api-gotchas.md` — 已知领域陷阱
- `templates/summary-design-template.md` — 概要设计模板
- `templates/detail-design-template.md` — 详细设计模板

### 步骤 3: 从 functional-spec.md 提取规格
对每个 SPEC 条目，直接提取并整理：
- SPEC id 和模块归属
- 状态（现有/增强/新）
- 类型（CRUD/workflow/integration/query/event/native-ui 等）
- 输入模式（字段、类型、校验规则、必填性）
- 处理步骤（平台无关的逻辑流）
- 输出/响应（成功结果、错误码）
- 依赖（内部模块、外部服务）
- 验收标准

由于 functional-spec.md 已经是机器可消费的结构，本步是直接提取，不需要解读散文或推断映射。

### 步骤 4: 推导架构决策
基于 functional-spec + CONSTRAINTS 推导：
- 模块在 CONSTRAINTS.md 定义的架构层级中的归属
- 跨模块的依赖方向（必须符合层级规则）
- 数据流和状态管理策略
- 横切关注点（认证、日志、错误处理）的接入点

### 步骤 5: 应用领域陷阱
- 读取 `harness/memory/domain-api-gotchas.md`
- 筛选与本 feature 涉及模块相关的条目
- 将陷阱转译为设计注释，例如：字段映射注意、不支持的组合、必要的前置条件、调用顺序约束
- 在 summary-design 和 detail-design 中以"陷阱注释"形式显式标注

### 步骤 5.5: 设计 Preview 前第一性原理反思

在 preview 设计方案或生成 summary/detail design 前，先复核：
- 此设计要交付的用户结果和业务闭环是什么。
- 哪些约束不可违背：functional-spec、review-conclusion、CONSTRAINTS、平台边界和已知领域陷阱。
- 最小可用设计是什么，哪些组件、集成或优化可以延后。
- 当前设计依据来自哪些 SPEC/文档/记忆条目，哪些推断需要标记。
- 如果当前设计失败，最可能是依赖方向、边界场景、状态流还是验收不可测；据此修正设计。

如存在 runtime，记录到 workpad：

```bash
python scripts/agent_runtime.py workpad --reflection "Design preview reflection: outcome={...}; constraints={...}; smallest design={...}; evidence={...}; failure mode={...}; design adjustment={...}"
```

### 步骤 6: 生成 summary-design.md
按 `templates/summary-design-template.md` 格式编写，包含：
- 问题陈述（从 product-spec-review.md 摘要）
- 方案概述（从 functional-spec 推导的架构思路）
- 架构层级归属（对照 CONSTRAINTS.md）
- 关键组件及其职责
- 数据流图（文本形式）
- 接口/依赖表（列出每个 SPEC 对应的模块和调用关系）
- 安全、性能、可用性等横切策略
- 与相关 SPEC id 的双向追溯

保持高层视图，不下探到具体函数签名或实现细节。

### 步骤 7: 生成 detail-design.md
按 `templates/detail-design-template.md` 格式编写，对每个 SPEC 条目展开：
- 接口/API 定义（方法签名、入参、出参、错误）
- 内部结构（类/模块划分）
- 算法描述（复杂逻辑）
- 错误处理策略（错误码映射、重试、降级）
- 状态转换（如涉及）
- 配置需求
- 边界情况处理

写得让 design-to-code 能直接将文档转换为代码骨架，无需重新解读产品散文。

### 步骤 8: 对照 CONSTRAINTS.md 验证
- 检查提出的模块划分是否符合层级规则
- 检查命名是否符合 AGENTS.md 中的命名规范
- 标记任何违反约束的情况并调整设计
- 无法调整时记录为待决问题供人工评审

### 步骤 9: 保存设计文档并写入追踪
- 将概要设计保存到 `docs/design/{design-area}/summary-design.md`
- 将详细设计保存到 `docs/design/{design-area}/detail-design.md`
- 不要把 summary/detail design 写回 `doc/features/{feature-name}/outputs/`；该目录只保留 PRD review、functional-spec、review-conclusion、goal-contract 等 feature 流水线产物
- 向 `harness/trace/` 写入追踪条目
- 如适用，更新 BLACKBOARD.md 的 feature 状态为 `designing`
- 如存在 runtime，写入 handoff：

```bash
python scripts/agent_runtime.py workpad --plan "Design complete; ready for design-to-code" --acceptance "summary-design and detail-design are generated" --validation "design checked against CONSTRAINTS.md"
python scripts/agent_runtime.py handoff --status planned --kind design-to-code --summary "{design summary}" --done "Generated summary-design and detail-design" --next "Run design-to-code for {feature-name}"
```

## 完成标准
- [ ] summary-design.md 存在且格式对齐模板
- [ ] detail-design.md 存在且格式对齐模板
- [ ] 每个 SPEC 条目都在设计中有对应设计段（无 SPEC 被遗漏）
- [ ] 设计已对照 CONSTRAINTS.md 验证层级合规
- [ ] 已处理相关的 domain-api-gotchas 条目
- [ ] 追踪条目已写入 harness/trace/
- [ ] runtime workpad/handoff 已指向 design-to-code（如果脚本存在）
- [ ] 未生成任何代码（Java/Kotlin/TS/Python 等）
- [ ] 未修改 prd-review 的输出文件

## 输出产物
- `docs/design/{design-area}/summary-design.md` — 概要设计
- `docs/design/{design-area}/detail-design.md` — 详细设计
- `harness/trace/` 中的追踪条目

## 错误处理
- **functional-spec.md 不存在** → 停止执行，提示用户先运行 prd-review，不要凭 PRD 原文猜测
- **functional-spec.md 条目不完整** → 标记缺失字段，路由回 prd-review 补齐，不要自行编造
- **设计违反 CONSTRAINTS.md** → 在步骤 8 标记违规，调整到合规；无法调整时记录为待决问题供人工评审
- **feature 目录不存在** → 提示用户：该 skill 依赖 prd-review 先跑过，若未跑请先执行 prd-review
- **domain-api-gotchas.md 尚无相关条目** → 记录"未发现相关陷阱"，不要编造陷阱
