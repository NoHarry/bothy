# 产品规格评审：{FeatureName}

> 本文档展示拟议的实现设计，供利益相关者确认。
> 每个部分涵盖一个功能区域。利益相关者审查每个项目并标记其确认状态。
>
> **使用方式**：审查每个部分。在每个部分的末尾，标记您的确认状态。
> **签署归口**：本文档是 PRD review 的唯一签署账本。所有 phase/gate closure、owner approval、API freeze、implementation freeze、release readiness 和文件级批准都必须汇总在本文档中；其他文档只能引用本文件，不得承载新的签字表。
> **中间记录归口**：`intermediates/decisions.md`、`issues.md`、`problems.md` 只保存证据和默认建议；其中需要人类确认的事项必须复制或汇总到本文档。确认完成后不逐行回写中间记录。

**需求来源**：{PRD/Spec/Ticket reference}
**版本**：{Version}
**日期**：{Date}

---

## 确认指南

每个部分末尾都包含一个确认块。请选择一项标记：
- `[x] 已确认` — 设计可接受，无异议
- `[x] 需修改` — 发现问题，详见评论
- `[x] 推迟` — 不在当前范围内
- `[x] 已移除` — 不再需要

---

## 人工确认项索引

> 生成具体文档时，将来自 PRD review、中间记录和风险评审的所有待确认项汇总到本节或各功能章节的确认块。`intermediates/` 可反向链接本节，但不维护独立状态。

| 来源 ID | 确认问题 | 主确认角色 | 关联章节 | 状态 |
|---------|----------|------------|----------|------|
| {Q1 / DEC-1 / RISK-1} | {question} | {role} | {section link} | 待确认 |

---

## 1. 范围与边界

### 包含内容

{Description of what this feature/module will handle}

| 职责 | 负责方 | 不负责方 |
|---------------|----------|-------------------|
| {responsibility} | {team/component} | {what's excluded} |

### 纳入/排除标准

> 何时应将功能纳入此模块，何时应其他地方处理？

| 标准 | 纳入 | 排除 |
|-----------|---------|---------|
| {criterion description} | {when to include} | {when to exclude} |

**利益相关者问题**
- [ ] 范围边界是否完整？
- [ ] 是否存在介于纳入和排除之间的边缘情况？

**利益相关者确认**：[ ] 已确认 / [ ] 需修改 / [ ] 推迟 / [ ] 已移除
**评论**：

---

## 2. 架构与分层

### 组件定位

{Where this feature fits in the overall architecture}

### 能力层

| 层级 | 描述 | 使用场景 | 示例 |
|-------|------------|----------|---------|
| {Layer name} | {what it does} | {when to use it} | {concrete example} |

### 设计原则

1. {Principle 1}：{explanation}
2. {Principle 2}：{explanation}

**利益相关者问题**
- [ ] 分层方式是否可接受？
- [ ] 设计原则是否完整？

**利益相关者确认**：[ ] 已确认 / [ ] 需修改 / [ ] 推迟 / [ ] 已移除
**评论**：

---

## 3. 功能设计：{Feature Area Name}

> {Overview: what this area covers, how many capabilities, design highlights}

### FEAT-{ID}：{Feature Name}

**需求摘要**
- 来源：{PRD section / ticket}
- 描述：{what the user/system needs}

**建议设计**
- 组件：{which component handles this}
- 类型：{CRUD / workflow / integration / query / etc.}
- 输入：
  | 字段 | 类型 | 约束 | 描述 |
  |-------|------|------------|-------------|
  | {field} | {type} | {validation rules} | {description} |

**处理流程**
```
1. Validate input → invalid → error response
2. Check preconditions → {precondition checks}
3. Execute core logic → {what happens}
4. Return result
```

**错误处理**
| 条件 | 响应 | 客户端操作 |
|-----------|----------|--------------|
| {error condition} | {error code/message} | {suggested handling} |

**设计笔记**：{rationale, tradeoffs, relationship to other features}

**利益相关者问题**
- [ ] {specific confirmation item}
- [ ] {specific confirmation item}

**利益相关者确认**：[ ] 已确认 / [ ] 需修改 / [ ] 推迟 / [ ] 已移除
**评论**：

---

{Repeat "## 3. Feature Design:" for each feature area}

---

## 4. 跨领域关注点

### 安全性
{Authentication, authorization, data protection considerations}

### 错误处理策略
{Common error handling patterns, error code taxonomy}

### 性能
{Performance requirements, SLAs, optimization considerations}

**利益相关者问题**
- [ ] 安全方案是否充分？
- [ ] 错误处理模式是否可接受？
- [ ] 性能目标是否现实？

**利益相关者确认**：[ ] 已确认 / [ ] 需修改 / [ ] 推迟 / [ ] 已移除
**评论**：

---

## 5. 新能力评估

> 需求中尚未有现有实现的功能。

### NEW-{N}：{Capability Name}

- 需求来源：{section/ticket}
- 现有能力：{is there something similar already?}
- 依赖：{what's needed from other teams/systems}
- 提案：{how to implement}

**利益相关者问题**
- [ ] 纳入当前范围？
- [ ] {other questions}

**利益相关者确认**：[ ] 已确认 / [ ] 需修改 / [ ] 推迟 / [ ] 已移除
**评论**：

---

## 最终签收

> 如 PRD 包含 phase/gate review、API freeze、implementation freeze、release readiness 或多文件批准，请在本节下新增对应角色/门禁表。不要拆到根 `docs/`、`review-conclusion.md`、`functional-spec.md` 或 `intermediates/`。

### {Role 1} 确认
- [ ] 所有功能项目已审查并确认
- [ ] 范围边界已同意
- [ ] 跨领域关注点已处理
- [ ] 新能力决策已做出

**签字**：
**日期**：

### {Role 2} 确认
- [ ] {role-specific checklist items}

**签字**：
**日期**：

{Repeat for each stakeholder role}

### 备注
