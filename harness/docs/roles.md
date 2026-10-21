# Roles In Harness Projects

## Project Owner

- 确认架构层级、模块边界和不能由 AI 猜测的业务规则。
- 维护 `templates/owner-action-board-template.md` 实例中的 OWNER-ACTION。
- 将确认后的规则推进到 `CONSTRAINTS.md`、`harness.yaml`、`.review/rules` 或业务文档。
- 决定何时从 `bootstrap` / `baseline` 切到 `strict`。

## Developer

- 新拉仓库或重新接手时运行 `workspace-doctor`。
- 本地缺工具、upstream、hooks 等 SELF-FIX 项由自己处理。
- 开发前阅读 README、AGENTS、CONSTRAINTS、BLACKBOARD。
- 提交必须走 `commit-review`，不能直接绕过本地 hooks。

## AI Agent

- 按 skill 流程执行，不凭聊天记忆推进。
- 能硬化的约束直接写入机械层。
- 不能确认的约束进入 OWNER-ACTION 或 `hardening_debt`。
- 业务变更时同步 harness context，确保后续 agent 能理解当前状态。

## Reviewer

- 使用 `.review/rules/*.md` 做项目级评审。
- 对缺少 harness 同步、缺少架构层映射、缺少负责人结论的提交扣分。
- 评分未达标时阻止提交。
