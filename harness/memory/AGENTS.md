# Harness Memory Agent 地图

`harness/memory/` 是 durable engineering memory，不是聊天摘要，也不是本地个人笔记。

## 文件职责
- `index.yaml`：所有 durable 条目的检索、证据、状态和作用域入口。
- `domain-api-gotchas.md`：领域/API 坑点。
- `process-improvements.md`：流程改进。
- `tool-environment-lessons.md`：工具和环境经验。
- `failure-patterns.md`：重复失败模式。
- `archive/`：distill 后的原始条目归档。

## 规则
- durable 条目必须包含 Type、Scope、Status、Confidence、Evidence。
- `local` 作用域只能写 ignored 的 `harness/.local/`，不能进入 durable memory。
- 新 memory 必须同步 `index.yaml` 并通过 `python scripts/lint_memory.py`。
- `harness-dream` 只能建议 memory 清理；实际合并、归档、去重交给 `memory-distill`。
- 不要把 runtime workpad、handoff、eval candidate 直接复制成 durable memory。
