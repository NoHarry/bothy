# Harness Dream Agent 地图

`harness/dream/` 是只读离线复盘入口。报告输出路径由 runtime helper 决定：模板仓库写 `harness/.local/dream/reports/`，目标项目写 `harness/dream/reports/`。

## 规则
- dream runner 只能读取 trace、memory、runtime 信号、hardening debt 和验证结果。
- dream report 只能给出 evidence、confidence、recommended_next_skill 和 owner action。
- 不得由 dream 直接修改 memory、eval、prompt、policy、规则或业务代码。
- 需要清理 memory 时转交 `memory-distill`；需要实现改动时转交对应开发 skill。
