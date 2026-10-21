# Harness Doc-Local Agent 地图

`harness/doc-local/` 只在 `doc.mode=submodule` 时承载本仓库本地草稿。

## 规则
- 当 `doc/` 是共享 submodule 且不能直接写入时，intermediates/generated 草稿可放这里。
- 最终机器可消费产物仍应进入对应 feature 的正式输出路径，经人工确认后再同步到共享 doc。
- 不要把 `harness/doc-local/` 当作长期 docs 或 durable memory。
