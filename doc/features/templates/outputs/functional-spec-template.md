# 功能规格：{FeatureName}

> 由 prd-review skill 生成，供设计技能使用（ref-to-design、design-to-code）。
> 每个功能/能力对应一个条目。结构化设计，便于机器处理。
> 仅包含在 product-spec-review.md 中确认的项目。

**需求来源**：{PRD/Spec/Ticket}
**生成日期**：{Date}
**相关评审**：`doc/features/{name}/outputs/product-spec-review.md`
**签署来源**：所有 owner sign-off、phase/gate approval、freeze/readiness approval 和文件级批准仅以 `product-spec-review.md` 为准；本文档不得承载签字表。

---

## SPEC-{ID}：{Feature Name}

- 模块：{which module/component}
- 状态：{existing | needs enhancement | new}
- 来源：{requirement section / FEAT-{ID} from spec review}
- 类型：{CRUD | workflow | integration | query | event | native-ui | etc.}
- 输入：
  | 字段 | 类型 | 约束 | 必填 | 描述 |
  |-------|------|------------|----------|-------------|
  | {field} | {type} | {validation} | {Y/N} | {description} |
- 处理：
  1. 验证输入 → 无效 → 错误
  2. 检查前置条件 → {checks}
  3. {core logic step}
  4. 返回结果
- 输出/响应：
  | 条件 | 结果 | 描述 |
  |-----------|--------|-------------|
  | 成功 | {success indicator} | {description} |
  | {error condition} | {error indicator} | {description} |
- 依赖：{external services, libraries, other modules}
- 验收标准：{specific, testable criteria}
- 开放项：{unresolved questions, if any; "None" if all clear}

---

{Repeat for each feature/capability}

---

## 摘要

| 类别 | 数量 |
|----------|-------|
| 现有（已实现） | {N} |
| 现有（需增强） | {N} |
| 新增 | {N} |
| 合计 | {N} |

（文件结束）
