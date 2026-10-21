# Git Hooks Agent 地图

`.githooks/` 是仓库本地机械门禁入口，由 `scripts/install_git_hooks.py` 安装和调度。

## 规则
- 不要在 hook 中加入凭证、远程环境假设或不可复现的本机路径。
- 新增阻断项前先放入 scripts 或 review rules，并更新 WORKFLOW、README 和 harness docs。
- baseline/strict hook 失败不能被静默吞掉；bootstrap profile 可以提示但不应假装通过。
- hooks 修改后运行 `python scripts/install_git_hooks.py --mode status` 和 `python scripts/validate.py --dry-run`。
