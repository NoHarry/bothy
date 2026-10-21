# Template Upgrade Handoff

> 本文只整理 harness 模板升级能力，方便在模板工作空间并行推进。当前业务问题处理时可先引用本文，不必展开模板实现细节。

## Goal

让已经接入 harness 的项目，在模板持续迭代后，可以安全吸收新增能力，例如：

- 新增或更新 skills
- 新增本地机械门禁脚本
- 新增 `.githooks`
- 新增 `.review/rules`
- 新增 harness docs/templates
- 新增 workspace-doctor / harness-dream / hook bus 等框架能力
- 新增 agent-runtime / eval governance 等长运行 agent 子任务能力

核心原则：先生成升级计划，再应用安全更新；项目化文件不自动覆盖。

## Background From Shared Discussion

相关分享页标题为 `Claude睡眠概念借鉴`，对模板后续演进有三类建议：

1. Claude Code / Claude Managed Agents 的 sleep / dreaming 概念。
2. 单 agent 和未来 PM/worker workspace 如何共享同一套 handoff/runtime contract。
3. 多工具场景下，Claude Code / Codex / OpenCode 的 hook 如何统一适配。
4. 多工具场景下，skill 如何统一管理并投影到不同工具目录。

这三类建议不要求当前一次性落完，但会影响模板升级能力的方向：模板会持续增加新 skill、hook adapter、policy、dream runner、skill projection 设施，因此旧项目必须有稳定的 `harness-upgrade` 入口来吸收这些能力。

### 1. Sleep / Dreaming 对 Harness 的启发

分享中的核心判断：不要把“睡眠”理解成模型真的休眠或训练自己，而应抽象为 agent 的离线维护循环。

在线阶段：

- agent 接收需求；
- 执行 skill；
- 修改代码或文档；
- 运行验证；
- 写 trace / memory。

离线阶段：

- 回看历史 trace；
- 压缩和清理 memory；
- 识别重复、矛盾、过期的经验；
- 从失败或返工中提炼约束；
- 生成 hardening_debt / owner action；
- 生成 eval 或测试债建议；
- 对 skill / hook / policy 给出改进建议。

分享中建议分三层推进：

| Layer | Purpose | Harness Candidate |
|---|---|---|
| Memory Sleep | 整理 memory，去重、压缩、消除矛盾 | `memory-distill` 增强或 `harness-dream` 建议 |
| Reflection Sleep | 从失败、绕过流程、返工中提炼规则和 eval | `scripts/dream_runner.py` + dream report |
| Harness Optimization Sleep | 分析 skill/hook/policy 是否需要优化 | 只输出建议，禁止自动改业务 |

对模板的含义：

- 已新增 `harness-dream` skill。
- dream runner 默认只读 `harness/trace/`、`harness/memory/`、`BLACKBOARD.md`、`hardening_debt`。
- dream 输出应进入模板仓的 `harness/.local/dream/reports/` 或目标项目的 `harness/dream/reports/`。
- dream 不应直接修改业务代码。
- dream 提出的规则硬化，必须再经过负责人确认或 `harness-executor` 落地。

建议 MVP：

1. 读取最近 N 个 trace。
2. 提取失败、绕过、重复人工提醒、缺失验证。
3. 输出三张表：
   - `Memory cleanup suggestions`
   - `Hardening candidates`
   - `Owner actions`
4. 不自动改文件。

### 2. Hook Adaptation 对 Harness 的启发

分享中的核心建议：不要为 Claude Code、Codex、OpenCode 分别写三套规则。正确结构是：

```text
Claude Code / Codex / OpenCode native hooks
        ↓
tool-specific adapter
        ↓
project-level harness hook bus
        ↓
policy / trace / memory / dream / eval gate
```

工具差异只留在 adapter 层，项目规则进入统一 harness 层。

分享中提到的差异：

| Tool | Hook 特点 | Harness 适配思路 |
|---|---|---|
| Claude Code | lifecycle hook 较完整，如 session、prompt、tool、stop、compact | adapter 转成标准事件 |
| Codex | hooks 需要配置开启；部分 hook 更像 guardrail | adapter 先覆盖可稳定拦截的事件 |
| OpenCode | 更适合 plugin 适配；可结合 permission 配置 | adapter/plugin 转成标准事件 |

建议定义统一事件模型：

```text
session_start
user_prompt_submit
pre_tool_use
post_tool_use
permission_request
stop
pre_compact
post_compact
session_idle
```

对模板的含义：

- 当前 `.githooks` 只解决 Git 提交前后，不等于 agent 工具生命周期 hook。
- 已新增 `scripts/hook_bus.py` dry-run/record-only 入口。
- 已新增 `harness/policy.example.yaml`；目标项目如需启用策略，可复制为 `harness/policy.yaml`。
- 已新增 tool adapter 文档骨架：
  - `.agent-harness/hooks/adapters/claude/`
  - `.agent-harness/hooks/adapters/codex/`
  - `.agent-harness/hooks/adapters/opencode/`
- hook bus 应统一写 trace event，供 dream runner 后续分析。

最小落地建议：

1. 先定义 `harness/policy.yaml`。
2. 先实现 `scripts/hook_bus.py --event pre_tool_use --payload {json}`。
3. 先做 Codex adapter 或当前团队最常用工具 adapter。
4. Claude/OpenCode adapter 后续补齐。

### 3. Skill Unified Management 对 Harness 的启发

分享中的核心建议：不要强求所有工具直接读同一个目录，而是统一作者体验，再投影到各工具运行目录。

推荐结构：

```text
unified skill source
        ↓
skills-sync / compiler
        ↓
Claude Code projection: .claude/skills/
Codex projection:      .agents/skills/
OpenCode projection:   .agents/skills/ or .opencode/skills/
```

目录认知：

| Tool | Project skill directory |
|---|---|
| Claude Code | `.claude/skills/<skill-name>/SKILL.md` |
| Codex | `.agents/skills/<skill-name>/SKILL.md` |
| OpenCode | `.opencode/skills`、`.claude/skills`、`.agents/skills` 均可能支持 |

对模板的含义：

- 当前模板已将 `.agent-harness/skills/src` 固化为统一源目录，`.agents/skills`、`.claude/skills`、`.opencode/skills` 都是投影目录。
- 统一源目录结构：

```text
.agent-harness/
  skills/
    registry.yaml
    registry.project.yaml
    src/
      workspace-doctor/
        SKILL.md
        overlays/
          claude.md
          codex.md
          opencode.md
```

- `scripts/sync_skills.py --write` 根据 registry 生成：
  - `.agents/skills/`
  - `.claude/skills/`
  - `.opencode/skills/`

建议 registry 字段：

```yaml
skills:
  - name: workspace-doctor
    source: src/workspace-doctor
    targets:
      codex: true
      claude: true
      opencode: true
    owner: infra
    status: active
```

工具特有说明放 overlay，不污染核心 `SKILL.md`：

- Claude Code overlay：Claude 专属 hooks、命令、目录说明。
- Codex overlay：Codex 桌面/CLI、`.agents/skills`、本地工具限制。
- OpenCode overlay：plugin 和 permission 说明。

对 `harness-upgrade` 的影响：

- `harness-upgrade` 已支持更新统一源目录和投影目录。
- 如果目标项目已经手工改过投影目录，`sync_skills.py --check` 会报 projection drift。
- 当前本地 hooks 已加入 `sync_skills.py --check`；CI 门禁可等模板稳定后再考虑。

### 4. How These Ideas Map To Current Template

当前模板已覆盖：

- `agent-runtime`：本地/worker agent 运行契约，记录 state、validation、eval、result、handoff 和 blocker。
- `workspace-doctor`：新人或回接手的在线入口。
- `harness-upgrade`：旧项目吸收模板新增能力。
- `.githooks`：Git 层本地机械门禁。
- `validate_harness_sync.py`：业务/流程变更必须同步 harness context。
- `hardening_debt`：不确定约束进入负责人处理队列。
- `harness-dream`：只读离线复盘报告。
- `hook_bus.py`：多 agent hook 事件统一入口，当前 dry-run/record-only。
- `sync_skills.py`：多 agent skill projection 写入和检查。

当前模板仍缺：

| Missing Area | Why It Matters | Suggested Artifact |
|---|---|---|
| Dream automation | 让 dream 建议自动进入 review queue | report-only 之后再设计 |
| Eval runner | 让 `harness/evals/` 可执行而不只是治理元数据 | 后续统一 runner |
| Hook enforcement | 多工具统一 policy 的 warn/block 阶段 | 负责人批准后启用 |
| Tool adapters | Claude/Codex/OpenCode 原生 hook 完整接线 | `.agent-harness/hooks/adapters/*` |
| Skill overlay support | 工具专属说明和核心 skill 的分层合成 | `.agent-harness/skills/src/*/overlays` |
| Projection CI gate | 在 CI 中强制 projection drift 检查 | `sync_skills.py --check` |

这些都属于模板后续增强，不建议混入当前业务项目修复。

## harness_framework.source

目标项目的 `harness.yaml` 应记录模板来源：

```yaml
harness_framework:
  schema_version: 1
  template_version: "1.2.0"
  source: "git@github.com:NoHarry/agent-harness-template.git"
  source_ref: "main"
  last_upgrade: null
```

模板仓库的 canonical 版本来自 `harness/VERSION`。目标项目升级前先比较 `harness.yaml` 中的 `harness_framework.template_version` 和最新模板 `harness/VERSION`；非 semver 的历史值按 legacy upgrade candidate 处理。

`source` 支持：

- HTTPS Git URL
- SSH Git URL
- scp 风格 SSH URL，如 `git@host:group/project.git`

提交到目标项目 Git 的 `harness.yaml` 应使用该项目实际使用的模板远程 Git URL。不同项目可以使用不同模板仓；不要套用其他项目的模板地址。开发者本机路径、WSL 路径、目标项目路径或 `"."` 只允许作为临时 `--source` override 或写入 ignored 的 `harness.local.yaml`，不能进入共享配置。

`source_ref` 建议填稳定分支或 tag。

## Upgrade Flow

已经有 `scripts/upgrade_harness.py` 的项目：

```bash
cd {project}
python scripts/upgrade_harness.py --target . --mode plan --write-report
python scripts/upgrade_harness.py --target . --mode apply --write-report
```

旧项目首次没有升级脚本时：

```bash
git clone {harness-template-url} {temp-template}
python {temp-template}/scripts/upgrade_harness.py --source {harness-template-url} --target {project} --mode plan --write-report
python {temp-template}/scripts/upgrade_harness.py --source {harness-template-url} --target {project} --mode apply --write-report
```

升级完成后：

```bash
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
python scripts/lint_template_version.py
python scripts/validate_harness_sync.py
python scripts/validate.py --dry-run
```

提交必须走 `commit-review`。

## Remote Template Cleanup

当 `source` 是远程 Git URL 时，升级脚本应：

1. clone 模板到系统临时目录；
2. 基于临时模板生成 plan 或 apply；
3. 写入升级报告；
4. 默认删除临时 clone。

只有排查问题时才使用 `--keep-source` 保留临时 clone。升级不应把模板仓库目录留在目标项目中。

## Auto Apply Scope

可自动添加或更新的框架拥有文件：

- `.agent-harness/`
- `.agents/skills/`
- `.claude/skills/`
- `.opencode/skills/`
- `.githooks/`
- `.review/rules/README.md`
- `.review/rules/harness-sync.md`
- `harness/docs/`
- `harness/evals/`
- `harness/policy.example.yaml`
- `harness/VERSION`
- `harness.example.yaml`
- `scripts/agent_runtime.py`
- `scripts/dream_runner.py`
- `scripts/hook_bus.py`
- `scripts/install_git_hooks.py`
- `scripts/harness_runtime.py`
- `scripts/lint_template_version.py`
- `scripts/lint_deps.py`
- `scripts/lint_quality.py`
- `scripts/lint_template_clean.py`
- `scripts/sync_skills.py`
- `scripts/upgrade_harness.py`
- `scripts/validate.py`
- `scripts/validate_harness_sync.py`
- `scripts/verify_action.py`
- `scripts/workspace_doctor.py`
- `templates/`

## Manual Merge Scope

以下项目化文件默认只进入 `MANUAL` 清单，不自动覆盖：

- `AGENTS.md`
- `BLACKBOARD.md`
- `CONSTRAINTS.md`
- `harness.yaml`
- `harness/policy.yaml`
- `doc/`
- `docs/`
- `harness/memory/`
- `harness/trace/`

`harness/.local/agent-runtime/` 属于本地运行记录，不在升级和提交范围内；如果它产生了有价值的 candidate，必须通过 memory-distill、owner action 或 eval review 提升。

原因：这些文件已经承载目标项目自己的架构、流程、负责人决策和历史经验，模板不能直接覆盖。

根 `README.md` 也属于目标项目自己的入口文档，但不按模板 README 做升级 diff 或 MANUAL 合并；只在项目 README 自身需要描述 Harness onboarding 时，按该项目真实状态更新。

## Backup Rule

`apply` 模式更新已存在文件前，先备份到：

```text
harness/upgrade-backups/{timestamp}/
```

默认不删除目标项目中的旧文件。删除类变更必须单独列为负责人确认项。

## Report Output

升级报告写入：

```text
harness/trace/{YYYY-MM-DD}-harness-upgrade-plan.md
harness/trace/{YYYY-MM-DD}-harness-upgrade-apply.md
```

报告至少包含：

- source / source_ref / resolved source
- target template version / source template version / version status
- ADD / UPDATE / MANUAL / SKIP 统计
- MANUAL 项清单
- 已应用文件和备份路径
- 后续验证命令

## Open Follow-ups

后续模板空间可继续推进：

1. `harness-dream`
   - 离线整理 trace/memory。
   - 输出 hardening_debt、owner action、eval/test debt 建议。
   - 不直接改业务代码。

2. `hook_bus`
   - 统一 Claude Code / Codex / OpenCode 的 hook adapter。
   - 真正规则进入 harness policy 层。

3. `skills-sync`
   - 增强 overlay 合成。
   - 支持按工具差异生成更薄的 projection。

4. `harness-upgrade` 增强
   - 支持 upgrade manifest。
   - 支持按能力选择升级。
   - 支持 dry-run 报告中的 owner action 自动生成。

5. `agent-runtime` 增强
   - 与 Codex SDK runner 的任务池和 worker workspace 无缝衔接。
   - 补充可执行 eval runner，但保留 runtime candidate -> reviewed repo eval 的边界。
