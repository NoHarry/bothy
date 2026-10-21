# Local Git Hooks

本模板使用仓库级 `.githooks/`，通过 `core.hooksPath=.githooks` 启用。不修改全局 Git 配置。

## Install

```bash
python scripts/install_git_hooks.py --mode install --profile baseline
```

新仓库没有远程或还在首次接入时使用：

```bash
python scripts/install_git_hooks.py --mode install --profile bootstrap
```

## Profiles

| Profile | Behavior | Use Case |
|---|---|---|
| bootstrap | 失败只告警，不阻断 | 新项目、无远程、首次接入 |
| baseline | 失败阻断 | 已有远程，baseline 或基础约束已建立 |
| strict | 失败阻断 | 成熟项目，规则已充分硬化 |

## Default Commands

`pre-commit`:

```bash
python scripts/lint_template_clean.py
python scripts/validate_harness_sync.py
python scripts/lint_deps.py
python scripts/lint_quality.py
```

`pre-push`:

```bash
python scripts/validate.py --dry-run
```

项目可在 `harness.yaml` 的 `git_hooks` 区域覆盖命令。
`install` 时传入的 profile 会写入本仓库本地 Git config `harness.hooksProfile`，优先于 `harness.yaml` 默认值；这样新仓库可以先用 `bootstrap`，负责人确认后再重新安装为 `baseline` 或 `strict`。

模板仓库模式下，`lint_template_clean.py` 会阻止提交本地运行记录。目标项目模式下该检查自动跳过。

## Existing Hooks

如果项目已有 `core.hooksPath`，安装脚本不会覆盖，除非显式传 `--force`。传统 `.git/hooks/pre-commit` 和 `pre-push` 会备份到 `.git/hooks/*.harness-backup-*`，状态记录在 `.git/harness-hooks-state.json`。

## Repository Scenarios

| Scenario | Command | Rule |
|---|---|---|
| 新项目，已有本地 git 但没有远程 | `python scripts/install_git_hooks.py --mode install --profile bootstrap` | 先提示不阻断，等负责人确认远程和分支策略后切 baseline |
| 新项目，还没有 git | 先由项目初始化流程决定是否 `git init` | `workspace-doctor` 不主动初始化项目 |
| 旧项目，已有远程 | `python scripts/install_git_hooks.py --mode install --profile baseline` | hooks 失败阻断本地提交 |
| 旧项目已有非 harness hooksPath | 先记录 OWNER-ACTION | 不使用 `--force`，除非负责人确认替换或合并 hook 链 |
| 基于旧项目在 harness 下重构 | `harness-onboard` 后安装 baseline | 不能自动硬化的规则进入 `hardening_debt` 或负责人行动板 |
