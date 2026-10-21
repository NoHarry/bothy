# Template Runtime Records

模板仓库需要能自我迭代，但模板维护过程中产生的运行记录不应随模板分发到目标项目。

## Runtime Roles

Harness 脚本按仓库角色选择记录路径：

| Role | Use Case | Runtime Output |
|---|---|---|
| `template` | harness 模板仓库自身迭代 | `harness/.local/` |
| `target` | 真实业务项目 | 标准 `harness/memory/`、`harness/trace/`；commit-review 证据放在评审输出 / agent runtime / MR 描述 |

角色判断顺序：

1. 环境变量 `HARNESS_REPO_ROLE`
2. 本地 `harness.local.yaml`
3. `harness.yaml` / `harness.example.yaml` 中的 `harness_runtime.repo_role`
4. 自动判断：存在 `harness.yaml` 时视为 `target`；否则有 `harness.example.yaml` 时视为 `template`

模板维护者如需显式指定，可创建本地文件：

```yaml
harness_runtime:
  repo_role: template
```

`harness.local.yaml` 被 `.gitignore` 忽略。

目标项目初始化后生成的 `harness.yaml` 是项目配置资产，必须可被 Git 跟踪和提交。模板仓库自身需要本地验证配置时，使用 `harness.local.yaml`，不要通过 `.gitignore` 忽略 `harness.yaml`。

## Quality Baseline

目标项目在 `quality.baseline_mode: "baseline"` 或 `"drift_gate"` 下生成的 `harness/baseline.json` 是团队共享基线，必须可被 Git 跟踪和提交。

模板仓库自我迭代时不能提交标准位置的 `harness/baseline.json`，否则下游项目会继承模板维护过程的本地质量快照。`lint_quality.py --baseline-init` 在模板仓库默认写入：

```text
harness/.local/baseline.json
```

`scripts/lint_template_clean.py` 会阻止模板仓库误提交 `harness/baseline.json`。

## Local Output Paths

模板模式下：

| Record | Path |
|---|---|
| validate performance | `harness/.local/memory/performance.md` |
| workspace-doctor trace | `harness/.local/trace/{date}-workspace-doctor.md` |
| harness-upgrade report | `harness/.local/upgrade-reports/{date}-harness-upgrade-{mode}.md` and `.json` |
| harness-dream report | `harness/.local/dream/reports/{date}-harness-dream.md` |
| hook bus events | `harness/.local/hook-events/{date}.jsonl` |

目标项目模式下仍使用原路径，但 `commit-review` 不再生成仓库级 `commit_log.csv`，避免多人提交时产生共享日志冲突。

## Clean Gate

`scripts/lint_template_clean.py` 在模板模式下阻止提交运行态记录：

- `commit_log.csv`
- `commit_log.xlsx`
- `harness/memory/performance.md`
- `harness/trace/YYYY-*.md`
- `harness/trace/hook-events/`
- `harness/dream/reports/`
- `harness/.local/`
- `harness/upgrade-backups/`

这样模板仓库可以保留本机运行历史，同时 Git 中只保留可继承的模板资产。
