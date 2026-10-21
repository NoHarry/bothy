# Harness Evals Agent 地图

`harness/evals/` 只存经 review 的仓库级 eval 元数据骨架。runtime eval 和 eval candidate 不能自动进入这里。

## 规则
- 新增 repo eval 前必须有 owner/review 结论和明确适用范围。
- runtime eval 只作为本次任务证据；提升为 repo eval 时要保留来源和理由。
- 不要在本目录放执行结果大文件、临时输出或本机数据。
- 修改本目录后运行 `python scripts/validate_harness_sync.py` 和 `python scripts/validate.py --dry-run`。
