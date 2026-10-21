# Skills 源 Agent 地图

本目录是 skill 唯一源。`.agents/skills/`、`.claude/skills/`、`.opencode/skills/` 都是生成投影。

## 修改流程
1. 编辑 `.agent-harness/skills/src/{skill}/SKILL.md` 或辅助脚本。
2. 新增/删除模板 skill 时同步 `.agent-harness/skills/registry.yaml`；目标项目自有 skill 同步 `.agent-harness/skills/registry.project.yaml`。
3. 运行 `python scripts/sync_skills.py --write`。
4. 运行 `python scripts/sync_skills.py --check`。
5. 如 skill inventory 或流程改变，同步 README、根 `AGENTS.md`、WORKFLOW 或相关 docs。

## 规则
- 不要在投影目录手工修 drift。
- skill frontmatter 的 `metadata.author` 统一使用 `noharry`，通过 canonical 源同步到各投影。
- `registry.project.yaml` 只能追加项目自有 skill，不得定义与模板 registry 同名的 skill；同功能项目约束应放到项目 overlay 或文档约束中。
- skill 文档中提到提交或推送时，必须保留 `commit-review` 入口。
- `commit-review` 不得生成或提交共享 `commit_log.csv` / `commit_log.xlsx`。
- skill 涉及 runtime 时，默认写 ignored 的 `harness/.local/agent-runtime/`。
