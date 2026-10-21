---
name: prd-review
description: 评审 PRD 文档的完整性和质量，产出结构化文档供下游设计和实现技能使用。任何功能实现前的第一步。当用户提到"评审 PRD"、"需求评审"、"prd review"、"功能规格评审"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: PRD Review

> 评审 PRD 文档的完整性和质量，产出结构化文档供下游设计和实现技能使用。

## 何时使用
- 开始实现任何功能之前
- 接手其他团队成员的 PRD 时
- 为冲刺规划新工作时
- 功能流程的第一步：prd-review → design → code → test → commit

## 前置条件
- PRD 文档路径（markdown、text 或 doc 格式）
- 功能名称（用于目录命名）
- harness.yaml 存在

## 工作流程

### Runtime handoff（默认）

如果存在 `scripts/agent_runtime.py`，本技能开始前先运行：

```bash
python scripts/agent_runtime.py init
python scripts/agent_runtime.py next --json
```

若存在匹配当前 PRD/feature 的 resumable run，先 `resume --run-id "{run id}" --json` 并从 workpad/handoff 继续；否则创建轻量计划 run：

```bash
python scripts/agent_runtime.py start --objective "prd-review {feature-name}" --task-type plan --role planner --json
```

### 步骤 0: 解析文档路径并检查现有输出

**解析文档写入路径**：
1. 从 harness.yaml 读取 `doc.mode`（默认值：`local`）
2. 设置路径：
   - **读取路径**（始终）：`{doc.path}/features/{feature-name}/`
   - **写入路径**：
     - mode=local → 与读取路径相同
     - mode=submodule → `harness/doc-local/{feature-name}/`

**检查现有输出**（跨仓库复用的关键）：
1. 检查 `{read-path}/outputs/functional-spec.md` 是否存在
2. 检查 `{read-path}/outputs/product-spec-review.md` 是否存在
3. 检查 `{read-path}/outputs/review-conclusion.md` 是否存在

| 现有输出 | 操作 |
|---------|------|
| 全部存在且完整 | **跳过评审。** 记录："输出已存在，消费现有 functional-spec.md。"标记技能为完成。 |
| 部分存在，部分缺失 | 提示用户："发现现有 {列表}。缺失：{列表}。仅生成缺失文件，还是重新运行完整评审？" |
| 全部不存在 | 执行完整评审（步骤 1 起） |

> 这实现了 submodule 场景：repo-android 已在共享 doc 中运行 prd-review 并生成输出。当 repo-ios 将同一 doc 链接为 submodule 时，prd-review 检测到现有输出并跳过。

### 步骤 1: 读取 PRD 文档

将完整 PRD 内容加载到上下文中。记录文档标题、作者和日期。

### 步骤 2: 创建功能目录

若不存在，则创建功能目录结构。

**mode=local**（直接写入 doc/）：
```
doc/features/{feature-name}/
├── src/                    # 将原始 PRD 复制到这里
├── intermediates/          # 讨论记录
│   ├── progress.md         # 来自模板
│   ├── decisions.md
│   ├── issues.md
│   ├── problems.md
│   └── learnings.md
├── generated/              # 用于迭代的草稿输出
└── outputs/                # 最终确认的文档
```
- 将源 PRD 复制到 `src/`
- 从 `doc/features/templates/intermediates/progress-template.md` 初始化 `intermediates/progress.md`
- 从模板创建空的中间文件（decisions、issues、problems、learnings）

**mode=submodule**（doc/ 是只读的，写入 harness/doc-local/）：
```
harness/doc-local/{feature-name}/
├── intermediates/          # 本仓库的讨论记录
│   ├── progress.md
│   ├── decisions.md
│   ├── issues.md
│   ├── problems.md
│   └── learnings.md
└── generated/              # 本仓库的草稿输出
```
- 不要写入 `doc/` — 它是共享的 submodule
- 从 `doc/features/{feature-name}/src/`（submodule）读取源 PRD
- 将所有中间文件和草稿写入 `harness/doc-local/{feature-name}/`

### 步骤 3: 检查必需章节

验证 PRD 中是否存在这些章节：

| 章节 | 必需 |
|------|------|
| 问题陈述 | 是 |
| 目标用户 | 是 |
| 功能需求 | 是 |
| 非功能需求 | 是 |
| 验收标准 | 是 |
| 范围外 | 是 |
| 依赖 | 是 |
| 时间线 | 是 |

如果任何章节缺失，在报告中标记。

### 步骤 4: 分析每个需求

对于列出的每个需求：
- 验证它是可测试的（你能编写基于此需求通过/失败的测试吗？）
- 验证它是无歧义的（没有模糊语言如"用户友好"或"快速"）
- 验证它有相应的验收标准

如果需求未通过任何检查，记录具体问题。

### 步骤 5: 检查边界情况和错误场景

审查需求中的：
- 错误处理需求
- 边界条件
- 负面测试场景
- 恢复过程

标记任何仅处理正常流程的需求集。

### 步骤 6: 检查冲突

查找：
- 同一功能内的矛盾需求
- 与现有系统行为冲突的需求
- 与其他功能重叠的范围

记录发现的任何冲突。

### 步骤 7: 验证非功能需求

对于每个 NFR，检查：
- 性能：具体数值（响应时间、吞吐量）
- 安全：认证、授权、数据保护
- 可扩展性：预期负载、增长预测
- 可用性：正常运行时间目标、维护窗口

模糊的 NFR（如"必须高性能"或"应该安全"）不足。

### 步骤 7.5: 评审 Preview 前第一性原理反思

在生成或向用户 preview `product-spec-review.md`、需求完整性评分或 review conclusion 前，先复核：
- 用户要解决的根问题和目标用户是否仍成立。
- 最小可实现价值是什么，哪些需求只是范围扩张。
- 需求中的不可违背约束、依赖和验收标准是否足以支撑实现。
- 当前评审结论基于哪些 PRD 证据，哪些是推断或缺口。
- 如果按当前评审推进，最可能失败在哪里，应该调整哪些范围、风险或阻碍描述。

如存在 runtime，记录到 workpad：

```bash
python scripts/agent_runtime.py workpad --reflection "PRD preview reflection: outcome={...}; smallest useful deliverable={...}; constraints={...}; evidence={...}; risks={...}; review adjustment={...}"
```

反思发现的缺口必须进入 `review-conclusion.md` 的风险/阻碍，而不是在 preview 中被淡化。

### 步骤 8: 生成 product-spec-review.md

使用 `doc/features/templates/outputs/product-spec-review-template.md` 模板：
1. 将所有审查的需求组织成功能区域
2. 每个区域填充：范围、架构影响、功能设计详情、输入/输出、错误处理
3. 每个部分添加利益相关者确认复选框
4. 包括横切关注点（安全、性能、错误处理）
5. 为没有现有实现的功能添加新能力评估
6. 添加带角色特定清单的总体签-off 部分
7. 将所有 PRD review 期间产生的签署、owner approval、phase/gate closure、API freeze、implementation freeze、release readiness、文件级批准或待签署清单汇总到本文件；不得另建 `docs/phase-*-gates.md`、`*-signoff.md` 或在 `review-conclusion.md` / `functional-spec.md` / `intermediates/` 中承载签字表
8. 对长周期、迁移、MVP、稳定性专项或其他易漂移需求，基于 `templates/goal-contract-template.md` 生成 `outputs/goal-contract.md`，并在本文件添加“目标契约”章节链接该文件；短 bugfix 或一次性小需求不强制生成
9. 若生成 `outputs/goal-contract.md`，从 PRD 的验收标准和风险中提取终态 checklist、结果计数和 no-progress 规则，并运行 `python scripts/lint_goal_contract.py --file {outputs}/goal-contract.md`

**先放在 `generated/` 中。** 利益相关者审核确认后，移到 `outputs/`。

`product-spec-review.md` 是 PRD review 的唯一签署账本。其他文档可以描述决策、风险、默认值或实现输入，但只能引用本文件中的签署章节，不能成为新的签署入口。

如果 `intermediates/decisions.md`、`intermediates/issues.md` 或 `intermediates/problems.md` 发现需要人类确认的问题，必须把确认项复制或汇总到 `product-spec-review.md` 的待确认问题、条目确认或最终签收章节。中间文件只能保留证据、默认建议和指向 `product-spec-review.md` 的确认入口，不得维护 `主确认方/协同确认方/待确认/已确认/签署人` 等独立确认状态表。确认闭环后，不要逐行回写中间文件；最多追加一条“已由 outputs/product-spec-review.md 闭环”的过程记录。

`goal-contract.md` 不是签署账本，也不写入 `harness.yaml`。它只约束本 feature/workstream 后续执行时的完成口径和结果计数，必须由 `product-spec-review.md` 或 `BLACKBOARD.md` 引用才对执行轮次生效。

### 步骤 9: 生成 functional-spec.md

使用 `doc/features/templates/outputs/functional-spec-template.md` 模板：
1. 将每个已确认的需求转换为 SPEC 条目
2. 每个条目包括：模块、状态（现有/增强/新）、类型、输入模式、处理步骤、输出/响应、依赖、验收标准
3. 保持规格**平台无关** — 描述功能做什么，而不是在每个平台上如何实现。平台决策在实现时由 design-to-code 做出
4. 使用 `Type` 表示实现性质：`CRUD`、`workflow`、`integration`、`query`、`event`、`native-ui`（平台特定 UI）等 — 这有助于 design-to-code 确定每个平台的适用性
5. 只包括通过审查的项目（没有未解决的阻碍）
6. 添加按类别计数的汇总表

**直接放在 `outputs/` 中。** 这是设计技能（ref-to-design、design-to-code）的主要输入。
不得在 `functional-spec.md` 中新增签字、owner sign-off、phase/gate approval 或 freeze approval 表；如需表达状态，只引用 `product-spec-review.md` 的签署章节。

### 步骤 10: 生成 review-conclusion.md

使用 `doc/features/templates/outputs/review-conclusion-template.md` 模板：
1. 总结范围决策：包含什么、什么被推迟/排除及理由
2. 列出所有已识别的风险和阻碍，包含影响、状态和负责人
3. 链接到相关文档（规范审查、功能规范、中间文档）

**放在 `outputs/` 中。**
不得在 `review-conclusion.md` 中新增签字、owner sign-off、phase/gate approval 或 freeze approval 表；它只能摘要风险和指向 `product-spec-review.md`。

### 步骤 11: 更新中间文档

记录审查会话：
- `intermediates/progress.md` — 会话日期、审查内容、阻碍、下一步
- `intermediates/decisions.md` — 审查期间做出的范围或设计建议、依据、替代方案；需要确认时只引用 `product-spec-review.md` 的确认项，不写独立确认状态
- `intermediates/issues.md` — 发现的问题、陷阱、证据和默认建议；需要确认时只引用 `product-spec-review.md` 的确认项，不写独立 owner/sign-off 表
- `intermediates/learnings.md` — 从这次审查中获得的可重用经验

### 步骤 12: 写入 Trace 条目

在 harness memory 中记录审查：
```
TRACE|prd-review|[date]|[doc-name]|completeness=[score]|blocking-issues=[count]|recommendation=[Ready|Revision Needed]|outputs=[product-spec-review,functional-spec,review-conclusion]
```

同时更新本地 runtime handoff，让后续任意 agent 可直接进入设计或需求修订：

```bash
python scripts/agent_runtime.py workpad --plan "PRD review complete; route to design or PRD revision" --acceptance "functional-spec and review-conclusion are ready or exceptions recorded" --validation "manual PRD completeness review"
python scripts/agent_runtime.py handoff --status planned --kind prd-review-to-design --summary "{review result}" --done "Generated/validated functional-spec and review conclusion" --next "{prd-to-design or revise PRD next step}"
```

## 完整性评分

对每个项目打分 0-1，汇总得出总分：

- [ ] 问题陈述清晰阐述原因
- [ ] 目标用户明确定义
- [ ] 所有功能需求可测试
- [ ] 所有功能需求有验收标准
- [ ] 非功能需求有具体指标
- [ ] 错误场景已处理
- [ ] 范围外已明确
- [ ] 依赖已识别
- [ ] 时间线现实且完整
- [ ] 需求内部无冲突

评分标准：
- 8-10 分：准备好实现
- 5-7 分：需要小幅修订
- 0-4 分：需要重大修订才能继续

## 完成标准
- [ ] 功能目录已创建，包含完整结构（src/、intermediates/、generated/、outputs/）
- [ ] 源 PRD 已复制到 src/
- [ ] product-spec-review.md 已生成（在 generated/ 或 outputs/ 中，取决于审批状态）
- [ ] 长周期或易漂移需求已生成 outputs/goal-contract.md 并在 product-spec-review.md 中引用；短任务已明确跳过
- [ ] functional-spec.md 已在 outputs/ 中生成
- [ ] review-conclusion.md 已在 outputs/ 中生成
- [ ] 中间文档已初始化并填充会话数据，且没有承载独立人工确认状态表
- [ ] Trace 条目已写入
- [ ] runtime workpad/handoff 已记录下一步（如果脚本存在）
- [ ] 完整性评分已计算并报告

## 输出产物
- `doc/features/{feature-name}/src/{original-prd}` — 源 PRD 副本
- `doc/features/{feature-name}/generated/product-spec-review.md` — 利益相关者审查草稿
- `doc/features/{feature-name}/outputs/product-spec-review.md` — 批准版本（签字后）
- `doc/features/{feature-name}/outputs/goal-contract.md` — 长周期/易漂移需求的目标契约（按需）
- `doc/features/{feature-name}/outputs/functional-spec.md` — 机器可消费规格
- `doc/features/{feature-name}/outputs/review-conclusion.md` — 范围和风险摘要
- `doc/features/{feature-name}/intermediates/` — 讨论记录
- `harness/trace/` 中的 Trace 条目

## 错误处理
- PRD 文件不存在 → 提示用户提供有效路径或创建新 PRD
- feature 目录冲突 → 检查现有输出，询问用户是覆盖还是保留现有版本
- 必需章节缺失过多 → 在 review-conclusion 中标记缺失章节，建议用户补充后再进行完整评审
- mode=submodule 但 doc/ 不是 submodule → 回退到 local 模式，将文档写入本地 harness/doc-local 目录

## 注意事项
- 没有验收标准的需求尚未准备好实现
- 模糊语言是危险信号。"系统应该快速" 不是需求。"95% 分位响应时间在 1000 并发用户下低于 200ms" 是需求
- 无法测试的需求应该被重写或移除
- functional-spec.md 是关键交接文档 — 设计技能直接消费它
- product-spec-review.md 经历 generated/ → 利益相关者审查 → outputs/ 的生命周期
- PRD review 签署唯一来源是 product-spec-review.md；phase/gate closure 草案、API freeze、release readiness 或文件级批准必须作为该文件的章节或表格存在，不能拆散到根 `docs/`、`review-conclusion.md`、`functional-spec.md` 或 `intermediates/`
