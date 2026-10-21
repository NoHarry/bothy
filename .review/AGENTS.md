# Review Rules Agent 地图

`.review/` 存放 commit-review 的项目级评审规则模板。

## 规则
- 只放评审规则、规则说明和本地 review 配置。
- 不要写提交日志 CSV；`commit-review` 的证据保留在评审输出、runtime、MR 描述或 review notes。
- 新增规则时说明触发条件、风险级别和修复建议。
- 如果规则可以机械执行，优先落到 scripts、harness.yaml 或 hooks。
