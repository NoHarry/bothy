# Scripts Agent 地图

`scripts/` 是 harness 的机械执行层。脚本应保持零或低依赖、可在目标项目复制后直接运行。

## 职责
- `validate.py`：完整验证编排。
- `validate_*` / `lint_*`：机械门禁。
- `agent_runtime.py`：ignored local runtime、workpad、handoff、validation、eval。
- `upgrade_harness.py`：已接入项目吸收模板新增能力。
- `workspace_doctor.py`：本地环境诊断和接手指引。
- `sync_skills.py`：canonical skill 到三端投影。
- `hook_bus.py` / `install_git_hooks.py`：多工具 hook 和 Git hooks。

## 规则
- 新脚本优先复用 `harness_runtime.py` 的路径、角色和 YAML 读取逻辑。
- 错误输出使用 What/Why/How 或明确的 `[OK]`、`[WARN]`、`[FAIL]`。
- 不要把模板本机运行记录写入可分发路径；模板模式用 `harness/.local/`。
- 修改验证、升级、runtime 或 hooks 后，同步 README、WORKFLOW、CONSTRAINTS 和相关 `harness/docs/`。
- 脚本变更至少运行 `python -m py_compile` 和相关专项验证。
