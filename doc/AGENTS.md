# Doc Pipeline Agent 地图

`doc/` 是需求和评审流水线数据目录，不等同于根级 `docs/`。

## 规则
- `doc/features/{feature}/src/` 放原始需求和设计资源。
- `intermediates/` 放评审过程记录。
- `generated/` 放待确认草稿。
- `outputs/` 放下游 skill 消费的最终 PRD review 产物，如 product-spec-review、functional-spec、review-conclusion、goal-contract。
- `product-spec-review.md` 是 PRD review 的唯一签署账本；phase/gate closure、owner approval、API freeze、implementation freeze、release readiness 和文件级批准不得拆到根 `docs/`、`review-conclusion.md`、`functional-spec.md` 或 `intermediates/`。
- `summary-design.md`、`detail-design.md` 是长期设计契约，默认放在 `docs/design/{design-area}/`，不要复制回 `doc/features/{feature}/outputs/` 形成两套来源。
- `doc.mode=submodule` 时，本仓库临时草稿优先放 `harness/doc-local/`。
