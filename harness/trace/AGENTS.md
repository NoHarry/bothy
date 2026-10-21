# Harness Trace Agent 地图

`harness/trace/` 是目标项目的标准任务追踪目录。模板仓库自维护 trace 使用 ignored 的 `harness/.local/trace/`。

## 规则
- 每个较完整任务应有一条 trace，记录目标、步骤、决策、验证、变更文件和经验教训。
- trace 是事实记录，不是长期规则；要沉淀规则时转写到 memory、CONSTRAINTS、review rules 或脚本。
- hook event、dream report、runtime workpad 不能直接混入标准 trace，除非经过显式 export/review。
- 模板仓库不得提交日期 trace；`lint_template_clean.py` 会阻止常见误提交。
