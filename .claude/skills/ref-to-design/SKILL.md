---
name: ref-to-design
description: 研究参考/外部代码并产出结构化设计文档。学习开源库、分析参考实现、或逆向 API/协议时使用。当用户提到"参考代码"、"分析实现"、"ref to design"、"设计文档"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: Reference to Design

> 研究参考/外部代码，产出结构化设计文档。在从现有实现学习后再构建自己的方案时使用。

## 何时使用
- 学习开源库并做适配
- 在构建类似功能前分析参考实现
- 从现有代码逆向工程 API 或协议

## 前置条件
- 参考代码可访问（本地路径或远程仓库）
- 目标项目已搭建 harness 基础设施
- 设计落点已确定：默认 `design-area={feature-name}`；目标项目可通过 overlay/CONSTRAINTS/WORKFLOW 改为 domain、module 或 bounded context 名称

## 工作流程

### Runtime handoff（默认）

如果存在 `scripts/agent_runtime.py`，先运行 `python scripts/agent_runtime.py next --json`。若存在匹配当前参考范围的 research/design run，先 `resume --run-id "{run id}" --json` 并从 workpad/handoff 继续；否则创建轻量研究 run：

```bash
python scripts/agent_runtime.py start --objective "ref-to-design {feature-name}" --task-type research --role researcher --json
```

### 步骤 1: 确定参考范围
- 确定要研究的参考代码具体范围
- 列出要分析的文件/模块
- 定义要提取的方面（架构、算法、模式、API 设计）
- 确定 `design-area`：优先使用用户指定值、参考代码模块名、WORKFLOW/CONSTRAINTS 或项目 overlay 中的 domain/module；否则使用 `{feature-name}`

### 步骤 2: 阅读并标注参考代码
- 阅读参考范围内的每个文件
- 对每个文件记录：
  - 用途和职责
  - 关键抽象（类、接口、函数）
  - 依赖（它导入/使用了什么）
  - 使用的模式（工厂、观察者、策略等）
  - 错误处理方式
  - 处理的边界情况

### 步骤 3: 提取架构模式
- 绘制模块依赖图
- 识别层级结构（如果有）
- 记录关注点分离
- 记录系统数据流

### 步骤 4: 识别需要适配的内容
- 哪些模式适用于我们的项目？
- 需要根据约束条件（CONSTRAINTS.md）做哪些修改？
- 哪些可以原样复用，哪些需要重新设计？
- 参考实现与我们的需求之间有哪些差距？

### 步骤 4.5: 参考方案 Preview 前第一性原理反思

在 preview 参考研究结论或适配计划前，先复核：
- 目标项目真正需要解决的问题是什么，参考实现解决的是不是同一个问题。
- 哪些约束不可违背：许可证、平台边界、架构层级、性能/安全要求和已有用户决策。
- 最小可借鉴内容是什么，哪些参考模式不应搬运。
- 当前结论基于哪些代码证据，哪些只是对参考项目语境的推断。
- 如果直接套用参考实现，最可能在哪些边界失败；据此调整适配方案。

如存在 runtime，记录到 workpad：

```bash
python scripts/agent_runtime.py workpad --reflection "Reference preview reflection: target outcome={...}; constraints={...}; minimal adaptation={...}; code evidence={...}; risks={...}; adaptation adjustment={...}"
```

### 步骤 5: 起草概要设计文档
按 `templates/summary-design-template.md` 格式编写：
- 问题陈述
- 提出的方案（从参考代码适配）
- 架构概述
- 关键组件及其职责
- 数据流图（文本形式）
- 依赖项和集成点

### 步骤 6: 起草详细设计文档
按 `templates/detail-design-template.md` 格式编写：
- 对每个组件：
  - 接口/API 定义
  - 内部结构
  - 算法描述（如果复杂）
  - 错误处理策略
  - 配置需求

### 步骤 7: 对照约束条件验证
- 对照 CONSTRAINTS.md 检查提出的设计
- 验证层级合规性
- 验证命名规范
- 标记任何违反约束的情况并调整设计

### 步骤 8: 检查领域陷阱
- 阅读 harness/memory/domain-api-gotchas.md
- 识别是否有已知陷阱影响此设计
- 在设计文档中添加缓解说明

### 步骤 9: 准备同行评审
- 列出待决问题和决策点
- 识别风险和未知因素
- 指出需要人工评审的领域

### 步骤 10: 保存设计文档
- 将概要设计保存到 `docs/design/{design-area}/summary-design.md`
- 将详细设计保存到 `docs/design/{design-area}/detail-design.md`
- `doc/features/{feature-name}/outputs/` 只作为 PRD review / functional-spec / goal-contract 来源，不承载长期 summary/detail design
- 向 harness/trace/ 写入追踪条目
- 如适用，更新 BLACKBOARD.md
- 如存在 runtime，写入 handoff：

```bash
python scripts/agent_runtime.py workpad --plan "Reference research complete; review adaptation decisions" --acceptance "reference findings are captured in design outputs" --validation "design checked against CONSTRAINTS.md"
python scripts/agent_runtime.py handoff --status planned --kind research-to-implementation --summary "{reference findings}" --done "Generated reference-based design" --next "Run design-to-code or review the adaptation decisions"
```

## 完成标准
- [ ] 产出概要设计文档
- [ ] 产出详细设计文档
- [ ] 设计已对照 CONSTRAINTS.md 验证
- [ ] 已处理已知陷阱
- [ ] 已写入追踪条目
- [ ] 待决问题已记录供人工评审
- [ ] runtime workpad/handoff 已记录研究结论和下一步（如果脚本存在）

## 输出产物
- `docs/design/{design-area}/summary-design.md` — 概要设计文档
- `docs/design/{design-area}/detail-design.md` — 详细设计文档
- harness/trace/ 中的追踪条目

## 错误处理
- **参考代码无法访问** → 尝试备用来源（镜像、缓存），如仍不可用则中止并通知用户
- **设计违反 CONSTRAINTS.md** → 在步骤 7 标记违规，调整设计直到合规，无法调整时记录为待决问题
- **feature 目录不存在** → 仍可基于参考范围生成 `docs/design/{design-area}/`；仅在需要 PRD/functional-spec 追溯时提示先补 feature 文档
