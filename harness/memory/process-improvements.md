# 流程改进

累积的开发工作流改进经验。
在底部追加新条目。永不删除现有条目。

## 格式

- **日期**：YYYY-MM-DD
- **来源**：这是在哪里发现的（例如：`harness/trace/2026-01-20-build-pipeline.md`）
- **之前**：之前是如何做的
- **之后**：改进后的方法
- **原因**：是什么促成了这个改变

---

<!-- 示例条目（添加真实条目时删除）：

### [2026-01-20] 先运行 lint 再运行测试

- **来源**：`harness/trace/2026-01-20-build-pipeline.md`
- **之前**：先运行完整测试套件，然后再 lint
- **之后**：先 lint，再测试
- **原因**：Lint 在 5 秒内捕获 80% 的问题；测试需要 2 分钟

-->

## mem-2026-05-12-001 - Skill 投影只能由唯一源生成

Type: procedural
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: skills, projection, codex, claude, opencode
Evidence:
- scripts/sync_skills.py
- .agent-harness/skills/registry.yaml
- .agent-harness/skills/src/skills-sync/SKILL.md

Lesson:
新增、删除、修改 skill 必须先改 `.agent-harness/skills/src/` 和 `.agent-harness/skills/registry.yaml`，再运行 `python scripts/sync_skills.py --write` 生成 `.agents/skills/`、`.claude/skills/`、`.opencode/skills/`。

Use when:
- 新增、删除、重命名或修改 skill
- 修复 Codex / Claude Code / OpenCode projection drift
- 检查模板升级后 skill 是否同步

Do not:
- 直接编辑 `.agents/skills/`
- 直接编辑 `.claude/skills/`
- 直接编辑 `.opencode/skills/`

Hardening:
- pre-commit 运行 `python scripts/sync_skills.py --check`
- `scripts/lint_agent_entrypoints.py` 防止入口规则漂移

## mem-2026-05-12-002 - harness-dream 只输出报告

Type: policy
Scope: template
Status: active
Confidence: high
Tags: dream, safety, memory
Evidence:
- scripts/dream_runner.py
- .agent-harness/skills/src/harness-dream/SKILL.md
- harness/docs/harness-dream.md

Lesson:
`harness-dream` 是离线复盘，不是自动自改。它可以读取 trace、memory、hardening debt 和验证信号，输出 memory cleanup、hardening、owner action 和 missing validation 建议，但不能直接修改源码、memory、eval、prompt、hook、policy 或 validation rules。

Use when:
- trace 数量增长
- 重复失败或跳过验证出现
- 模板升级后需要复盘
- memory 噪音增多

Do not:
- 在 dream pass 内直接改 memory
- 让同一个 agent 同时提出和发布规则变更

Hardening:
- dream report 后续必须转成 owner action、`memory-distill` 或普通 Harness task

## mem-2026-05-12-003 - 模板运行记录只进 harness/.local

Type: policy
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: template, runtime, local
Evidence:
- scripts/harness_runtime.py
- scripts/lint_template_clean.py
- harness/docs/template-runtime.md

Lesson:
模板仓库可以自我迭代，但运行态记录不能作为模板资产分发。模板仓库的 commit log、performance、trace、dream report、upgrade report 和 hook events 必须写入 ignored 的 `harness/.local/`。

Use when:
- 在模板仓库运行 validate、workspace-doctor、harness-dream、harness-upgrade 或 commit-review
- 判断某个运行记录是否应该提交

Do not:
- 把模板维护过程产生的 `commit_log.csv`、日期 trace 或 performance log 提交到标准路径

Hardening:
- `scripts/harness_runtime.py` 根据 repo role 路由运行路径
- pre-commit 运行 `python scripts/lint_template_clean.py`

## mem-2026-05-12-004 - harness-upgrade apply 后还要审计 MANUAL 项

Type: procedural
Scope: template
Status: active
Confidence: high
Tags: upgrade, manual-merge, downstream
Evidence:
- scripts/upgrade_harness.py
- .agent-harness/skills/src/harness-upgrade/SKILL.md
- harness/docs/harness-upgrade.md

Lesson:
`harness-upgrade apply` 只同步框架拥有文件。升级完成前必须再次 plan，确认 `ADD/UPDATE=0`，再逐项人工合并 `AGENTS.md`、`CONSTRAINTS.md`、`harness.yaml`、`.gitignore`、memory 等 MANUAL 项。根 `README.md` 不按模板 README 做 diff 或 MANUAL 合并；如项目 README 需要 Harness onboarding 内容，只按该项目真实状态维护。

Use when:
- 已接入项目吸收新模板能力
- 新 skill、hook、agent adapter 或 runtime policy 被模板引入

Do not:
- 仅凭 apply 成功宣称升级完成
- 自动覆盖项目化的入口文档和 memory

Hardening:
- `harness-upgrade` skill 明确要求 MANUAL merge checklist
- `templates/harness-upgrade-plan-template.md` 保留 MANUAL 审计项

## mem-2026-05-18-003 - 下游 hardening delta 必须回灌模板

Type: procedural
Scope: template
Status: active
Confidence: high
Tags: upgrade, downstream, hardening, convergence
Evidence:
- scripts/upgrade_harness.py
- scripts/harness_runtime.py
- scripts/validate.py
- .agent-harness/skills/src/commit-review/SKILL.md

Lesson:
当目标项目已经是当前模板版本但 `harness-upgrade plan` 仍出现框架拥有文件 `UPDATE` 时，通常说明目标项目保留了本地 hardening delta。应先判断这些 delta 是否属于通用模板能力；若属于，应回灌模板源仓库并重新 plan 到 `ADD/UPDATE=0`，再清理目标项目中的升级 hardening debt。

Use when:
- 目标项目 `harness.yaml` 记录 template-local delta
- 同版本升级 plan 仍存在框架文件 UPDATE
- 下游修复涉及 parser、validation、skill 或 hook 通用行为

Do not:
- 在目标项目长期保留可通用化的框架修复
- 仅修改目标项目后宣称模板升级完成

Hardening:
- 回灌后必须用最新模板对目标项目重新运行 `harness-upgrade plan`

## mem-2026-06-01-001 - Preview 前先做第一性原理反思

Type: procedural
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: preview, planning, review, reflection, agent-runtime
Evidence:
- WORKFLOW.md
- CONSTRAINTS.md
- scripts/agent_runtime.py
- harness/docs/agent-runtime-contract.md
- .agent-harness/skills/src/harness-executor/SKILL.md

Lesson:
需求 preview、计划预览、调研摘要或评审结论输出前，agent 必须先从第一性原理复核用户结果、不可违背约束、最小可用交付、证据、风险和需要调整的计划/评审点，并把结论写入 runtime workpad reflection。这个反思点应在执行前校准范围和风险，而不是提交前才补做。

Use when:
- 接到需求后要给用户 preview 理解、计划或实现路线
- 输出 PRD review、ref-to-design、prd-to-design 或 design-to-code 的阶段性结论
- 评审计划或设计时需要区分证据和推断

Do not:
- 把 reflection 当成泛泛总结或事后复盘
- 在 evidence 不足时继续猜测需求或项目策略
- 把该流程误放到 commit-review 专属阶段

Hardening:
- `scripts/agent_runtime.py workpad --reflection` 记录反思
- WORKFLOW 和核心 skill 在 preview 前要求 reflection

## mem-2026-06-16-001 - 目标项目自有 skill 使用 registry.project.yaml

Type: procedural
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: skills, projection, upgrade, downstream
Evidence:
- scripts/sync_skills.py
- scripts/test_sync_skills.py
- harness/docs/skills-sync.md
- harness/docs/harness-upgrade.md

Lesson:
模板主 registry 只承载模板拥有的 skill。目标项目如果有模板没有等价能力的项目专用 skill，应将 skill 源放在 `.agent-harness/skills/src/<skill>/`，并用 `.agent-harness/skills/registry.project.yaml` 声明。`sync_skills.py` 合并模板 registry 和项目 registry 生成投影；项目 registry 不得覆盖模板同名 skill。同功能旧项目 skill 应迁移为模板 skill 加项目 overlay，而不是保留第二套 skill。

Use when:
- 目标项目升级模板后需要保留项目专用 Codex/Claude/OpenCode skill
- `harness-upgrade plan` 因主 registry 混入项目 skill 持续显示 UPDATE
- 判断旧项目 skill 是否应由模板同名 skill 替代

Do not:
- 把项目专用 skill 追加到模板主 registry
- 用 project registry 覆盖模板同名 skill
- 为同功能流程保留两套 skill

Hardening:
- `sync_skills.py` 同时读取 `registry.yaml` 和 `registry.project.yaml`
- duplicate skill name 会导致 projection check 失败
- `registry.project.example.yaml` 作为目标项目示例
