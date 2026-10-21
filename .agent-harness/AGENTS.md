# Agent Harness 适配 Agent 地图

`.agent-harness/` 是多 agent 工具适配的源目录，负责 registry、hook adapter 说明和 canonical skill 源。

## 规则
- 修改 skill 必须进入 `.agent-harness/skills/src/`，不要直接改工具投影目录。
- 新增/删除模板 skill 时同步 `.agent-harness/skills/registry.yaml`；目标项目自有 skill 放入 `.agent-harness/skills/registry.project.yaml`。
- adapter 当前阶段只做 check/dry-run；强阻断策略需要 owner 明确批准。
- 变更后运行 `python scripts/sync_skills.py --write` 和 `python scripts/sync_skills.py --check`。

## 子目录
- `skills/AGENTS.md`：skill 源与投影规则。
- `hooks/adapters/`：工具 hook adapter 占位和演进说明。
