# 功能

此目录包含功能级需求、需求评审和生成输出。

## 结构

每个功能有自己独立的文件夹：`{feature-name}/`

```
doc/features/
├── templates/              # 新功能的模板文件
│   ├── src/               # 原始需求文档（PRD、规格说明等）
│   ├── intermediates/     # 评审过程中的讨论记录
│   ├── generated/         # 用于评审迭代的草稿输出
│   └── outputs/           # 最终评审通过并批准的文件
├── {feature-name}/        # 实际的功能文件夹
│   ├── src/               # 此功能的源需求
│   ├── intermediates/     # 评审讨论记录
│   ├── generated/         # 评审草稿文档
│   └── outputs/           # 最终批准文档
└── README.md              # 本文件
```

## 功能文件夹命名

- 日期格式：`YYYY_MM_DD`（例如 `2026_04_07`）
- 描述性格式：小写、连字符分隔（例如 `user-auth-v2`）
- 每个文件夹包含：`src/`、`intermediates/`、`generated/`、`outputs/`

## outputs/ — PRD Review 交付物

prd-review 技能在 `outputs/` 中生成 3 个文档：

| 文件 | 用途 | 受众 | 下游消费者 |
|------|---------|----------|-------------------|
| `product-spec-review.md` | 干系人确认文档。按功能区域组织。每个条目有确认复选框。 | 产品负责人、干系人 | 人工评审和签收 |
| `functional-spec.md` | 功能级规格。每个功能/能力一条目。为机器消费而结构化。 | 开发团队、AI 流水线 | 设计技能（prd-to-design、ref-to-design） |
| `review-conclusion.md` | 评审决策摘要。范围包含/排除、风险、阻碍。 | 内部团队 | 归档 |

`product-spec-review.md` 是 PRD review 的唯一签署账本。所有干系人确认、phase/gate closure、API freeze、implementation freeze、release readiness 和文件级批准都必须汇总在该文件的 generated/outputs 版本中；`functional-spec.md`、`review-conclusion.md`、`intermediates/` 和根级 `docs/` 只能引用它，不能承载新的签字表或 owner sign-off ledger。

## 流程

```
需求（src/）
  → prd-review 技能
  → product-spec-review.md（generated/ → 干系人评审 → outputs/）
  → functional-spec.md（outputs/）
  → review-conclusion.md（outputs/）
  → 设计技能（消费 functional-spec.md）
  → docs/design/{design-area}/summary-design.md
  → docs/design/{design-area}/detail-design.md
```

`summary-design.md` 和 `detail-design.md` 是长期设计契约，默认由 `prd-to-design` 或 `ref-to-design` 写入 `docs/design/{design-area}/`。`doc/features/{feature}/outputs/` 只保留 PRD review、functional-spec、review-conclusion、goal-contract 等一次性 feature 流水线产物。

## intermediates/ — 讨论记忆

评审过程中积累的记录，跨会话累积：
- `issues.md` — 评审中发现的问题和坑
- `decisions.md` — 已确认的决策及其理由
- `problems.md` — 阻碍及其解决状态
- `learnings.md` — 可复用的经验教训
- `progress.md` — 跨会话进度跟踪器和阻碍日志

（文件结束 — 共 59 行）
