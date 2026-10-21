# Harness Docs Agent 地图

`harness/docs/` 只写 harness 框架自身说明，不写目标业务 feature 设计。

## 放什么
- 框架能力说明、升级说明、workflow/runtime/memory/dream/goal contract 文档。
- `examples/**/harness.yaml` 这类可复制配置示例。
- 面向已接入项目的升级 handoff 和 operator 指南。

## 不放什么
- 具体业务功能或领域设计；放到目标项目根级 `docs/design/{design-area}/`。
- 目标项目的人类工程文档；放到目标项目根级 `docs/`。
- 模板维护过程的运行记录；模板仓库写到 `harness/.local/`。

## 维护规则
- 新增框架能力时，至少检查 README、相关 docs、`harness/docs/examples/**/harness.yaml` 是否需要同步。
- 示例配置的 `harness_framework.template_version` 必须和 `harness/VERSION` 一致。
- 文档不能承诺 hook 或 adapter 已强阻断，除非对应脚本已经实现并进入验证流程。
