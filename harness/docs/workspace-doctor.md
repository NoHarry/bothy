# Workspace Doctor

`workspace-doctor` 用于新开发者首次拉仓库、长期未维护后重新接手、或本地环境不确定时的自检。它只维护开发者环境和下一步指引，不实际推进业务任务。

## Recommended Command

```bash
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
```

With `--fix-safe`, doctor may install local hooks and initialize `harness/.local/agent-runtime/`. It always checks `python scripts/agent_runtime.py next --json` and reports resumable local runs as `RESUME-FIRST`, including workpad path, pending completion count, and pending validation when available. It also reports the active Python version and whether PyYAML is installed, because advanced YAML syntax requires `harness/requirements.txt`. With `--run-checks`, doctor runs the safe local Harness checks including skill projection, agent runtime status, memory index validation, dependency lint, quality lint, and `validate.py --dry-run`.

## Output Groups

| Group | Meaning | Who Handles It |
|---|---|---|
| RESUME-FIRST | 本地 runtime 中可续接的 plan/research/review/implementation workpad/handoff | 开发者或当前 agent 优先读取 |
| AUTO-FIXED | 已自动处理的本地事项，如安装 hooks | 开发者确认即可 |
| SELF-FIX | 开发机缺工具、未配置 upstream 等 | 开发者处理 |
| OWNER-ACTION | 项目规则、负责人决策、缺失输入 | 项目负责人 |
| READ-FIRST | 接手前必须阅读的上下文 | 开发者 |
| CHECKS | 工具和验证命令结果 | 开发者 |
| NEXT-STEPS | 下一步动作 | 开发者或负责人 |

## Boundaries

- 可以安装本仓库本地 Git hooks。
- 可以初始化 ignored 的 agent runtime 目录。
- 可以读取 ignored 的 runtime handoff，并提示先恢复未完成上下文。
- 可以提示缺失工具和缺失上下文。
- 可以运行只读检查。
- 模板仓库中 `--write-trace` 写入 `harness/.local/trace/`，不会作为模板资产分发。
- 不创建新功能、不修改业务代码、不替负责人做业务决策。

## Android Projects

Android 项目要求开发者本机安装 Android CLI，并确保 `android` 命令在 PATH 中可用。安装地址：<https://developer.android.com/tools/agents/android-cli/archive?hl=zh-cn>
