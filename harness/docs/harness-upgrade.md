# Harness Upgrade

`harness-upgrade` 用于已经接入 harness 的项目吸收模板后续新增能力，例如新增 skill、脚本、本地 hooks、文档模板或评审规则。

`harness_framework.source` 可以是：

- HTTPS Git URL：`https://github.com/NoHarry/agent-harness-template.git`
- SSH Git URL：`git@github.com:NoHarry/agent-harness-template.git`
- scp 风格 SSH：`git@host:group/project.git`

提交到目标项目 Git 的 `harness.yaml` 应记录该项目实际使用的模板远程 Git URL 和稳定分支/tag。不同项目可以使用不同模板仓；不要套用其他项目的模板地址。开发者本机路径、WSL 路径、目标项目路径或 `"."` 只能作为临时调试输入传给 `--source`，或写入 ignored 的 `harness.local.yaml`，不得进入共享 `harness.yaml`。

推荐在目标项目 `harness.yaml` 中记录远程 URL 和稳定分支/tag：

```yaml
harness_framework:
  schema_version: 1
  template_version: "1.5.7"
  source: "git@github.com:NoHarry/agent-harness-template.git"
  source_ref: "master"
  source_commit: null
  last_upgrade: null
```

`harness/VERSION` 是模板仓库的版本唯一来源。目标项目的 `harness.yaml` 记录它当前吸收过的模板版本；旧的非 semver 版本会被视为 legacy upgrade candidate。`source_commit` 是可选的 40 位 commit SHA pin：设置后，`plan/apply` 会先解析模板源 HEAD 并校验一致，commit 不匹配时在写入任何文件前失败。

注意：pin 是"校验后拒绝"机制，不是"按 commit 拉取"——远程模板始终按 `source_ref` 的当前 HEAD 浅克隆。当远程分支前进导致 pin 落后时，这不是 bug：负责人核对模板新变更后把 `source_commit` 更新为新 HEAD 再升级；如需长期固定某个版本，让 `source_ref` 指向模板仓库的 tag 并把 pin 设为该 tag 对应的 commit。

## Scope

自动升级只覆盖框架拥有的文件：

- `.agents/skills/`
- `.agent-harness/`
- `.githooks/`
- `.claude/skills/`
- `.opencode/skills/`
- `.review/rules/harness-sync.md`
- `harness/docs/`
- `harness/evals/`
- `harness/policy.example.yaml`
- `harness/requirements.txt`
- `harness/VERSION`
- `harness.example.yaml`
- `scripts/` 中的 harness 工具脚本
- `templates/`
- `scripts/validate_workflow.py`
- 框架拥有的局部 `AGENTS.md`：`.agent-harness/AGENTS.md`、`.agent-harness/skills/AGENTS.md`、`.githooks/AGENTS.md`、`.review/AGENTS.md`、`doc/AGENTS.md`、`harness/**/AGENTS.md`、`scripts/AGENTS.md`、`templates/AGENTS.md`

新增的 `scripts/agent_runtime.py` 和 `harness/evals/` 属于框架拥有文件；运行过程中生成的 `harness/.local/agent-runtime/**` 永远不提交，也不由 upgrade 同步。

`CLAUDE.md` 是 add-only：目标项目缺失时可添加；如果已存在且内容不同，则进入 `MANUAL`。它只能作为 `AGENTS.md` 的薄 adapter，不能承载项目规则。

`WORKFLOW.md` 也是 add-only：目标项目缺失时可添加；如果已存在且内容不同，则进入 `MANUAL`。它是仓库级执行契约，不能被升级脚本静默覆盖。

默认不覆盖项目化文件：

- `.gitignore`
- `AGENTS.md`
- `CONSTRAINTS.md`
- `BLACKBOARD.md`
- `WORKFLOW.md`
- `harness.yaml`
- `harness/policy.yaml`
- `doc/`
- `docs/`
- `harness/memory/`
- `harness/trace/`

这些文件只进入 `MANUAL` 项，由负责人或维护者决定如何合并。框架拥有的局部 `AGENTS.md` 是例外：它们用于描述 harness 组件维护边界，可由 apply mode 导入；根 `AGENTS.md` 仍然只做 MANUAL 合并。

目标项目可保留项目自有 skill，但不要把它们追加到模板主 registry。项目自有 skill 应放在 `.agent-harness/skills/src/<skill>/`，并用 `.agent-harness/skills/registry.project.yaml` 声明；`scripts/sync_skills.py` 会合并模板 registry 和项目 registry 后生成三端投影。项目 registry 不得定义与模板同名的 skill；同功能规则应使用模板 skill，并通过项目 overlay、`CONSTRAINTS.md`、`WORKFLOW.md`、`harness/docs/` 或 memory 加强项目约束。根 `AGENTS.md` 只记录入口地图和关键清单变化。

根 `README.md` 不参与升级 diff，也不会进入 `MANUAL` 清单。模板仓库的 README 只描述模板自身，目标项目应按自己的产品、构建、部署、开发者入口和实际 Harness 接入状态维护 README。

## Plan First

```bash
python {template}/scripts/upgrade_harness.py --target {project} --mode plan --write-report
```

命令未显式传 `--source` 时，会读取目标项目 `harness.yaml` 的 `harness_framework.source`。输出中会先给出：

```text
[SOURCE-IDENTITY] kind={local|remote} ref={...} commit={...} dirty={true|false|null}
[VERSION] target={target harness.yaml template_version} source={template harness/VERSION} status={...}
```

当 `status=upgrade-available`、`target-version-legacy` 或 `target-version-missing` 时，继续 review 文件计划；当 `status=same-version` 时，只应在修复框架漂移时 apply。

## Apply Safe Updates

```bash
python {template}/scripts/upgrade_harness.py --target {project} --mode apply --write-report
```

`UPDATE` 文件会备份到 `harness/upgrade-backups/{timestamp}/`。
`harness/upgrade-backups/` 被忽略，不应提交。
带 `--write-report` 时，脚本会同时写 Markdown 报告和同名 `.json` sidecar；JSON 中包含 `source_identity`、actions、applied 文件、备份路径和 SHA256 证据。

## Remote Source Cleanup

当 `source` 是远程 Git URL 时，脚本会：

1. clone 模板到系统临时目录；
2. 基于临时模板生成 plan 或 apply；
3. 写入升级报告；
4. 默认删除临时 clone。

只有排查问题时才使用 `--keep-source` 保留临时 clone。升级不会把模板仓库作为目录留在目标项目中。

## After Upgrade

```bash
python {template}/scripts/upgrade_harness.py --target {project} --mode plan
python scripts/sync_skills.py --check
python scripts/agent_runtime.py status --json
python scripts/lint_agent_entrypoints.py
python scripts/lint_template_version.py
python scripts/lint_harness_config.py
python scripts/lint_goal_contract.py
python scripts/lint_prd_review_signoff.py
python scripts/validate_workflow.py
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
python scripts/validate_harness_sync.py
python scripts/lint_memory.py
python scripts/validate.py --dry-run
python scripts/lint_deps.py
python scripts/lint_quality.py
```

再次 plan 时 `ADD` 和 `UPDATE` 必须为 0。剩余 `MANUAL` 项不是失败，但必须逐项判断是否需要合并到项目化文件。

常见必须人工吸收的内容：

- 根 `AGENTS.md`：只在 skill 入口清单、读文顺序、关键路径、分层 agent 地图入口或三端投影入口变化时合并；不要把协议细节、坑点或流程正文追加到根地图。
- `.agent-harness/skills/registry.project.yaml`：确认项目自有 skill 是否确实没有模板等价能力；同功能旧 skill 应迁移为模板 skill + 项目 overlay，而不是保留第二套 skill。
- 项目 `README.md`：不合并模板 README；仅在项目 README 本身承载 Harness onboarding 时，按该项目真实状态补充必要命令或说明。
- 局部 `AGENTS.md`：确认框架拥有的局部地图已由 apply 导入；如目标项目已有同名自定义文件，按 MANUAL 规则审阅差异。
- `CONSTRAINTS.md`：`.agent-harness/skills/src` 是 skill 唯一编辑源，`.agents/.claude/.opencode` 是生成目录，`CLAUDE.md` 只能链接 `AGENTS.md`，`harness-dream` 只输出报告。
- `WORKFLOW.md`：新增任务类型、默认 skill、validation profile 或人工确认点时需要负责人合并。
- `harness.yaml`：`harness_framework.template_version/source/source_ref/last_upgrade`，以及 build/test 等 owner action。
- `harness.yaml`：如项目采用 commit pin，同步确认 `harness_framework.source_commit` 是实际模板 HEAD 的完整 40 位 SHA。
- `.gitignore`：`harness/.local/`、`harness/upgrade-backups/`、`*.harness-backup-*`、`harness.local.yaml` 和 Python cache；不要忽略目标项目共享的 `harness/baseline.json`。
- `harness/memory/`：记录升级过程中发现的流程缺陷或项目特有教训；新增 durable memory 时同步 `harness/memory/index.yaml` 并通过 `lint_memory.py`。

如果新增 skill 已投影，但项目入口文档仍未提到它们，升级不能视为完成。若 `validate.py --dry-run` 对有效项目配置给出 unknown top-level warning，应回补模板 schema 或调整配置结构后再提交。

如果新增能力需要项目负责人决策，写入 `hardening_debt` 或 owner action board。提交必须走 `commit-review`。

模板仓库自身运行升级计划时，报告写入 ignored 的 `harness/.local/upgrade-reports/`；目标项目仍写入 `harness/trace/`。
