# 约束规则

> 机械执行层。此文件中的每条规则都可以通过脚本或 linter 检查。
> Agent 在进行任何代码更改前必须查阅此文件。

## L0 — 不可变规则
<!-- 永远不能违反的规则 -->
- 永远不要抑制类型错误（禁止 `@ts-ignore`、`# type: ignore`、`@SuppressWarnings` 等）
- 永远不要提交 secrets、credentials 或 API keys
- 永远不要为了通过测试而删除或削弱现有测试
- 永远不要为了制造进度而放宽验收条件、golden fixture、reference baseline、回放 oracle 或结果分类
- planner、auditor、证据包、脚手架和基础设施整理不得替代已绑定 `goal-contract.md` / `BLACKBOARD.md` 中的结果计数推进
- 所有更改必须在提交前通过验证流程
- 所有提交必须通过 `commit-review` skill；长运行本地进度保存用 checkpoint mode，push/MR/交付用 publish mode；不得直接绕过本地 hooks 或手工提交

## L1 — 架构规则
<!-- 层级依赖方向 -->
- 依赖只能向下流动：{LAYER_N} → {LAYER_N+1} → ... → {LAYER_BOTTOM}
- 第 N 层模块不得从第 N-1 层或更高层导入
- 横切关注点（logging、auth、config）应放在 shared/common 层
<!-- 占位符：在此定义项目的层级 -->

## L2 — 命名与结构规则
<!-- 文件、类、函数的命名规范 -->
- 文件名：{kebab-case, PascalCase, snake_case}
- 类名：PascalCase
- 函数/方法名：{camelCase, snake_case}
- 常量：UPPER_SNAKE_CASE
- 每个文件一个公开类/组件
<!-- 占位符：按语言定制 -->

## L3 — 代码质量规则
- 最大文件长度：{N} 行（默认：300）
- 最大函数长度：{N} 行（默认：50）
- 禁止硬编码 magic numbers 或 strings — 使用命名常量
- 禁止空的 catch/except 块
- 生产代码中禁止 console.log/print/System.out.println — 使用proper logging
- 所有公开 API 必须有文档注释

## L4 — 技能流程规则
- ref-to-design 必须在 design-to-code 开始前产出设计文档
- `summary-design.md` 和 `detail-design.md` 默认写入 `docs/design/{design-area}/`；`doc/features/{feature}/outputs/` 只保留需求评审、functional-spec、review-conclusion 和 goal-contract 等 feature 流水线产物
- design-to-code 必须在 code-to-test 开始前通过 build 和架构 lint
- code-to-test 必须在 commit-review 前达到覆盖率目标
- commit-review publish mode 必须在 push/MR/交付前通过完整验证流程
- checkpoint mode 只允许本地 WIP/checkpoint，不允许 push、目标分支 fan-out 或 MR；publish mode 必须对累计 diff 做完整评审
- 每次技能执行必须在 harness/trace/ 中产生追踪条目
- 日常开发或修 bug 任务应使用 `scripts/agent_runtime.py` 记录本地 runtime state、validation、eval、result 和 handoff；运行记录只能在 ignored 的 `harness/.local/agent-runtime/`
- 需求 preview、计划预览、调研摘要或评审结论输出前，必须从第一性原理反思用户结果、不可违背约束、最小可用交付、证据、风险和需要调整的计划/评审点，并用 runtime workpad reflection 记录（如可用）
- workspace-doctor 只负责开发者环境诊断和下一步指引，不得实际推进业务需求或替负责人决策
- 任务若绑定 `doc/features/{feature}/outputs/goal-contract.md`，每轮结束必须记录“哪个结果计数动了、旧值、新值、验证证据”；无计数变化的工作只能作为辅助说明，不能宣称为主进度
- 正常执行轮次不得为了降低难度修改已绑定的 `goal-contract.md`；只有需求/阶段终态达成、owner 明确改目标、主工作流切换或 blocker 后 owner 决策时才允许变更目标契约
- 等待 owner/human gate 时，只记录缺口和下一步；禁止围绕等待项新造 planner、auditor、证据包或文档工作来填充进度
- 证据缺口（缺回放语料/基线/分组 runner）不算受阻：参照系统（旧构建、参考实现、golden 环境）可达时，按目标契约的采集流程先采基线（记 trace）再继续；只有环境物理不可用才允许记 skip/PENDING，且必须写明恢复后必补项
- 连续 2 轮没有任何结果计数变化时，停止主动扩展工作，写入不超过 10 行的阻塞说明并等待负责人决策

## L5 — Harness 维护规则
- 发现新的领域坑点 → 追加到 harness/memory/domain-api-gotchas.md，并为 durable 条目更新 harness/memory/index.yaml
- 发现流程改进 → 追加到 harness/memory/process-improvements.md，并为 durable 条目更新 harness/memory/index.yaml
- 学会工具/环境教训 → 追加到 harness/memory/tool-environment-lessons.md，并为 durable 条目更新 harness/memory/index.yaml
- durable memory 必须包含证据、作用域、状态和置信度；`local` 作用域只能进入 ignored 的 `harness/.local/`
- 需求、阶段或目标口径变更时必须更新对应 feature/workstream 的 `goal-contract.md`（如存在）和 `BLACKBOARD.md`，并记录触发条件、owner/决策来源、日期和旧阶段归档摘要
- 追踪条目必须在执行期间撰写，不能事后补写
- 不能立即确认的约束必须进入 `hardening_debt` 或负责人行动板，不能由 agent 硬猜
- 已接入项目吸收模板新增能力必须走 `harness-upgrade`，先 plan 后 apply
- 模板版本唯一来源是 `harness/VERSION`；大版本或框架能力变更必须同步 `harness.example.yaml`、示例配置、README Version History 和升级文档
- 模板仓库的运行态记录必须写入 ignored 的 `harness/.local/`，不得提交到可被下游继承的标准 trace/memory/log 路径
- `.gitignore` 不得忽略 `harness.yaml`；模板自举配置只能放在 ignored 的 `harness.local.yaml`，目标项目生成的 `harness.yaml` 必须可提交
- `.gitignore` 不得忽略 `harness/baseline.json`；目标项目质量基线是共享资产。模板仓库本地基线必须写入 ignored 的 `harness/.local/baseline.json`，并由 `lint_template_clean.py` 阻止误提交标准 baseline。
- `harness-dream` 只允许生成只读报告，不得直接修改 memory、eval、prompt、policy、规则或业务代码
- `WORKFLOW.md` 只能描述仓库内执行契约，不得包含凭证字段、运行时根目录、端口、全局并发、sandbox 设置或 review/validation 绕过策略
- runtime eval 和 eval candidate 只能作为运行证据或候选项；经 review 后才允许进入 `harness/evals/`，第一版不允许 agent 自动发布 repo eval
- `memory-distill` 修改 memory 前必须先输出 merge plan，归档原始文件，同步 `harness/memory/index.yaml`，并通过 `python scripts/lint_memory.py`
- skill 唯一编辑入口是 `.agent-harness/skills/src/` 和 registry；模板 skill 使用 `.agent-harness/skills/registry.yaml`，目标项目自有 skill 使用 `.agent-harness/skills/registry.project.yaml`；项目 registry 不得覆盖模板同名 skill，`.agents/skills/`、`.claude/skills/`、`.opencode/skills/` 是生成投影，不得手工改
- `CLAUDE.md` 只能作为 `AGENTS.md` 的薄 adapter，不得写入项目规则、偏好、流程或 skill 指令
- 根 `AGENTS.md` 只做项目地图；框架组件细节必须放到最近的局部 `AGENTS.md` 或 `harness/docs/`。局部 `AGENTS.md` 只能细化根规则，不能放宽根规则。
- 已接入项目升级时，框架拥有的局部 `AGENTS.md` 由 `harness-upgrade` 导入；根 `AGENTS.md` 等项目文件仍为 MANUAL 合并项。
- `hook_bus` 当前阶段只能 dry-run/check；强阻断必须由负责人批准后再进入策略

## L6 — Harness 同步规则（强制）

> 缺少 harness 更新的更改是**不完整**的。
> 在任何代码或架构更改后，查阅此表并更新所有受影响的文件。
> `scripts/validate_harness_sync.py` 是本地机械检查；`commit_log.csv` 不算同步证据。
> 根 `AGENTS.md` 只做入口地图：只有读文顺序、关键路径、skill 清单、目录地图或不可放宽规则变化时才更新。细节默认进入 `CONSTRAINTS.md`、`WORKFLOW.md`、局部 `AGENTS.md`、`harness/docs/`、memory 或 trace。

### 更改 → 更新映射

| 更改类型 | 默认落点 |
|-------------|-------------|
| 架构 / 层级 / 模块重构 | `CONSTRAINTS.md`（硬规则）、`README.md`（目录结构）、必要时局部 `AGENTS.md`；根 `AGENTS.md` 只更新入口级架构摘要或关键路径 |
| 新增或修改公开 API / 接口 | API 文档（如存在）、`README.md`（重大入口变化）；根 `AGENTS.md` 只在关键文件索引变化时更新 |
| 新增或修改技能 | `.agent-harness/skills/src/`、registry、投影、`harness/docs/skills-sync.md`；根 `AGENTS.md` 只在可用 skill 入口清单变化时更新 |
| 新增本地机械门禁 / hooks / 验证脚本 | `WORKFLOW.md`、`harness.yaml`/示例配置、`harness/docs/`、`.review/rules/`、README；根 `AGENTS.md` 只在读文顺序或不可绕过规则变化时更新 |
| 新增 harness 模板能力 | `harness/VERSION`、`harness/docs/`、README Version History、`WORKFLOW.md`、相关 `templates/`、`harness/evals/` 和 `upgrade_harness.py` 框架清单；局部 `AGENTS.md` 只记录组件边界，根 `AGENTS.md` 只记录入口地图变化 |
| 目标、终态或结果计数口径变更 | 对应 feature/workstream 的 `goal-contract.md`（如存在）、`BLACKBOARD.md` 结果覆盖率、`WORKFLOW.md` 任务类型/汇报规则 |
| 命名规范或编码规则变更 | `CONSTRAINTS.md`（硬规则）和 `.review/rules/`（可审查规则）；根 `AGENTS.md` 只保留最高优先级不可放宽摘要 |
| 新领域功能 / 重大实现 | `harness/memory/` + `harness/memory/index.yaml`（坑点、流程、工具教训）、`harness/trace/`（执行记录）、`BLACKBOARD.md`（状态更新） |
| 关键 bug 或意外陷阱 | `harness/memory/domain-api-gotchas.md` 或 `harness/memory/failure-patterns.md`、`harness/memory/index.yaml`；根 `AGENTS.md` 不承载坑点正文 |
| 流程或工作流改进 | `harness/memory/process-improvements.md`、`harness/memory/index.yaml` |
| 工具 / 环境 / 构建问题 | `harness/memory/tool-environment-lessons.md`、`harness/memory/index.yaml` |
| 新增或升级依赖 | README（重大依赖或安装入口）、相关配置/lockfile；根 `AGENTS.md` 只在影响项目导航或硬规则时更新 |
| 配置或部署变更 | `README.md`、相关 harness 文档 |

### 更改后检查清单（agent 在标记任务完成前必须验证）

1. [ ] `AGENTS.md` — 入口地图、读文顺序、关键路径、skill 清单和不可放宽摘要是否仍然准确？是否仍保持短地图而非手册？
2. [ ] `CONSTRAINTS.md` — 层级规则、命名规则、质量规则是否仍然准确？
3. [ ] `README.md` — 目录结构、工作流图、配置示例是否仍然准确？
4. [ ] `WORKFLOW.md` — 任务类型、默认 skill、validation profile 和人工确认点是否仍然准确？
5. [ ] `goal-contract.md` / `BLACKBOARD.md` — 目标契约和结果覆盖率是否已更新（如任务绑定目标契约或需求相关）？
6. [ ] `harness/memory/` — 是否有新的坑点、流程改进或工具教训需要记录，并同步 index？
7. [ ] `harness/trace/` — 是否已为此任务撰写执行追踪？
8. [ ] `scripts/validate_workflow.py`、`scripts/validate_harness_sync.py` 和 `scripts/lint_memory.py` — 是否通过？
9. [ ] `.review/rules/` — commit-review 是否能检查到本次新增规则？

### Memory 条目规则

- **仅追加**：永不删除或修改现有条目
- **必须注明来源**：每条条目必须引用知识来源（追踪文件、报告或任务）
- **必须可操作**：条目必须描述问题及解决方案，而不仅仅是观察

## 执行
- `scripts/lint_deps.py` 执行 L1（架构规则）
- `scripts/lint_quality.py` 执行 L3（代码质量规则）
- `scripts/validate_harness_sync.py` 执行 L6（harness 同步规则）
- `scripts/validate_workflow.py` 检查 `WORKFLOW.md` 执行契约
- `scripts/install_git_hooks.py` 安装和运行本地 Git hooks
- `scripts/lint_template_clean.py` 防止模板仓库提交本地运行记录
- `scripts/lint_template_version.py` 检查模板版本元数据一致性
- `scripts/lint_agent_entrypoints.py` 防止 Claude/Codex 等 agent 入口产生规则漂移
- `scripts/lint_goal_contract.py` 检查现有 feature/workstream 目标契约章节齐全、终态 checklist 和结果计数口径有效；无目标契约时跳过
- `scripts/workspace_doctor.py` 诊断开发者本地环境和下一步指引
- `scripts/upgrade_harness.py` 规划或应用已接入项目的 harness 框架升级
- `scripts/agent_runtime.py` 管理本地/worker agent 运行契约、handoff、runtime eval 和候选项
- `scripts/dream_runner.py` 生成只读离线复盘报告
- `scripts/hook_bus.py` 统一 Claude Code / Codex / OpenCode hook 事件
- `scripts/sync_skills.py` 检查多 agent skill 投影状态
- `scripts/verify_action.py` 在执行前预验证操作
- `scripts/validate.py` 运行完整流程：build → lint-arch → test → verify
