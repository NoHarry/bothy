# 评审结论：{FeatureName}

## 概述

{One paragraph: requirement background, goals, scope}

**需求来源**：{PRD/Spec/Ticket}
**评审日期**：{Date}
**评审状态**：{In Progress / Concluded / Pending Follow-up}

## 范围决策

### 纳入当前范围

| # | 功能 | 受影响组件 | 备注 |
|---|---------|-------------------|-------|
| {N} | {feature name} | {components/modules} | {notes} |

### 推迟/排除

| # | 功能 | 原因 | 计划版本 |
|---|---------|--------|-------------|
| {N} | {feature name} | {why excluded} | {target release/sprint} |

## 风险与阻碍

> 风险状态只描述风险处理进度，不表示签署或人工确认状态；需要确认的问题必须指向 `product-spec-review.md`。

| ID | 描述 | 影响 | 状态 | 负责人 |
|----|------------|--------|--------|-------|
| RISK-{N} | {description} | {what's affected} | {open/mitigated/resolved} | {person/team} |

## 相关文档

- 规格评审：`doc/features/{name}/outputs/product-spec-review.md`
- 功能规格：`doc/features/{name}/outputs/functional-spec.md`
- 讨论记录：`doc/features/{name}/intermediates/`

## 签署归口

签字、owner sign-off、phase/gate approval、API freeze、implementation freeze、release readiness 和文件级批准只记录在 `product-spec-review.md`。本文档只摘要风险和结论，不承载签署表。

（文件结束）
