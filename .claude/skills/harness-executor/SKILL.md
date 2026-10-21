---
name: harness-executor
description: 使用完整 harness 流程执行日常开发任务（功能、修复、重构），确保质量和可追溯性。任何开发任务都应使用此 Skill 而非临时编码。当用户提到"开发功能"、"修复 bug"、"重构"、"harness executor"、"执行任务"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: Harness Executor

> 使用完整的 harness 流程执行日常开发任务。这是日常开发工作的主要技能。

## 何时使用

- 任何开发任务（功能、修复、重构）
- 使用此技能而非临时编码，以确保质量和可追溯性

## 前置条件

- Harness 基础设施已存在（AGENTS.md、CONSTRAINTS.md、WORKFLOW.md、harness/、scripts/）
- 如不存在：自动调用 harness-creator 技能，然后继续
- 目标项目的 harness.yaml 中已配置项目特定命令。基础模板仓库可只包含 harness.example.yaml，此时应先运行 project-init 或 harness-creator 生成真实 harness.yaml。
- 如果存在 `scripts/agent_runtime.py`，本次任务必须创建或复用一个 runtime run，用于记录状态、workpad、验证、result 和 handoff。

## 工作流程概览
```
环境检查 → 加载上下文 → 规划 → 确认 → 执行 → 验证 → 完成
```

## 工作流程

### 步骤 0: 环境检查

1. 验证项目根目录存在 AGENTS.md
   - 如缺失 → 调用 harness-creator 技能，然后继续
2. 验证 harness.yaml 存在
   - 如缺失但存在 harness.example.yaml → 这是基础模板状态；先调用 project-init 或 harness-creator 生成目标项目配置
   - 如缺失且不存在 harness.example.yaml → 警告并创建最小配置
3. 验证 scripts/ 存在
   - 如缺失 → 警告用户，跳过自动验证
4. 如存在 `WORKFLOW.md`，运行 `python scripts/validate_workflow.py`
   - 如失败 → 修复 workflow 结构或记录 OWNER-ACTION，不要绕过其 human gate
5. 运行 `python scripts/install_git_hooks.py --mode status`
   - hooks 未安装 → 提示先运行 `workspace-doctor` 或安装 hooks
   - hooksPath 冲突 → 记录 OWNER-ACTION，不强行覆盖
6. 初始化 agent runtime（如果脚本存在）：
   - `python scripts/agent_runtime.py init`
   - `python scripts/agent_runtime.py next --json`
   - 若存在与当前请求匹配的 resumable run，先执行 `python scripts/agent_runtime.py resume --run-id "{run id}" --json` 并从 workpad/handoff 继续。
   - 若没有匹配 run，执行 `python scripts/agent_runtime.py start --objective "{task summary}" --task-type implementation --role executor --json`。
   - 这个检查是默认动作，不需要用户专门要求"接着昨天的计划"。
7. 读取当前 git 状态（如果是 git 仓库）
   - 如有未提交更改 → 警告用户，建议提交或 stash

### 步骤 1: 加载上下文

按顺序读取以下文件（找不到则跳过）：
1. AGENTS.md — 了解项目结构、规范、可用技能
2. CONSTRAINTS.md — 了解需遵守的规则
3. WORKFLOW.md — 确认任务类型、默认 skill、validation profile 和 human gate
4. BLACKBOARD.md — 检查相关需求
5. harness/memory/index.yaml — 根据任务 tag 选择需要读取的 durable memory
6. harness/memory/domain-api-gotchas.md — 避免已知陷阱
7. harness/memory/process-improvements.md — 遵循改进的流程
8. harness/memory/tool-environment-lessons.md — 避免工具问题
9. harness/docs/agent-runtime-contract.md — 确认 runtime/handoff 边界
10. harness/trace/ 中最近的追踪条目（最近 3 条）— 了解近期上下文

如当前 BLACKBOARD 需求、PRD 输出或 handoff 绑定 `doc/features/{feature}/outputs/goal-contract.md`，在读取 CONSTRAINTS/WORKFLOW 前先读取该文件，并把“终态判定”和“结果计数”作为本轮进度口径。短 bugfix、一次性小任务或未绑定目标契约的维护任务不强制创建目标契约。

### 步骤 2: 理解任务

1. 解析用户请求
2. 确定此任务关联到哪个 BLACKBOARD 需求（如果有）
3. 如当前任务绑定 goal-contract.md，确定本轮要移动的结果计数；长周期维护流通常移动 maintenance 计数（P0/P1 bug、回归、核心 smoke、flaky test、shipped-path mock/stub）；如果没有计数能移动，记录 blocker，不用 meta 工作填充
4. 确定任务类型：功能、修复、重构、迁移、文档、测试
5. 估算范围：小（1-3 个文件）、中（4-10 个文件）、大（10+ 个文件）
6. 确定受影响的架构层级

### 步骤 2.5: Preview 前第一性原理反思

当本轮需要向用户 preview 需求理解、执行计划、评审结论或关键取舍时，先从第一性原理复核一次，再输出 preview：

1. 用户真正要达成的结果是什么，而不是字面上要求改哪一行。
2. 哪些约束不可违背：AGENTS/CONSTRAINTS/WORKFLOW、数据安全、平台边界、已确认的用户决策。
3. 最小可用交付是什么，哪些内容是后续增强或可延后范围。
4. 当前证据来自哪里，哪些判断只是推断。
5. 最大失败模式是什么，计划或评审结论需要因此调整什么。

如存在 `scripts/agent_runtime.py`，将反思写入 workpad：

```bash
python scripts/agent_runtime.py workpad --reflection "First principles preview: outcome={...}; constraints={...}; smallest useful deliverable={...}; evidence={...}; risks={...}; adjustment={...}"
```

若反思发现需求、证据或约束不足，不要继续猜测；记录 confusion/blocker，并给出 Human Action Required。

### 步骤 3: 预验证操作

在编写任何代码之前，对每个计划的文件变更：
1. 运行 `python scripts/verify_action.py --action "{action}" --changes "{changes}"`
2. 如有任何操作被 BLOCKED → 在继续前修改计划
3. 记录验证结果

### 步骤 4: 创建执行计划

生成编号计划，包含：
- 要创建/修改/删除的文件
- 操作顺序
- 步骤间的依赖关系
- 复杂任务的检查点标记（每更改 3-5 个文件）

对于大范围任务：
- 拆分为多个阶段
- 每个阶段必须通过验证后才能开始下一阶段
- 设置明确的检查点：保存追踪、运行验证

对绑定 goal-contract 的重复单元流（迁移工具组、bug backlog、端点改写等），按"会话 + 批"计划而不是单切片轮次：
- 以同 pattern/同 client/同基类的 8-15 个单元为一批；剩余不足或单元明显复杂时可更小，并在记账行写明原因
- 会话内连续推进多批：每批通过 commit-review checkpoint mode 形成本地 checkpoint；publish 前再做完整 commit-review，批间不重读上下文文档，只查增量
- 长构建/测试可后台执行，但被验证的工作区必须保持不变；等待期间只能做只读分析/整理下一批，除非使用独立 worktree/snapshot 隔离后再编辑
- 昂贵环境操作（设备安装、环境切换、基线采集循环）按会话合并成一次循环，不要每批做一次
- 只在三种情况停：当前组/阶段完成、上下文接近耗尽、触发 goal-contract 的无进度规则

### 步骤 5: 执行计划

对计划中的每个步骤：
1. 宣布你正在做什么
2. 进行变更
3. 在变更的文件上运行 lsp_diagnostics（如适用）
4. 如有错误 → 在继续前立即修复
5. 记录 runtime 事件（如果脚本存在）：
   - `python scripts/agent_runtime.py event --type progress --phase "{phase}" --message "{short update}"`
   - 计划、验收、验证、注意事项或疑惑变化时，追加到 `workpad`：`python scripts/agent_runtime.py workpad --plan "{item}" --acceptance "{criterion}" --validation "{command}" --note "{note}"`
6. 在检查点 → 运行 `python scripts/validate.py`
7. 将检查点验证结果写入 runtime：
   - `python scripts/agent_runtime.py validation --command "{command}" --status pass|fail|skip --summary "{summary}"`

执行期间的规则：
- 严格遵守 CONSTRAINTS.md
- 如当前任务绑定 goal-contract.md，优先实现能移动结果计数的最小纵向切片；planner、auditor、证据包、脚手架或纯基础设施整理不能作为主进度
- 证据缺口不等于受阻：所选切片缺回放语料/基线/oracle 行而参照系统（旧构建、参考实现、golden 环境）可达时，按目标契约的采集流程先采基线（记 trace）再 diff；只有环境物理不可用才允许记 skip/PENDING，且必须写明恢复后必补项
- 禁止条款按其声明范围执行：回放 plan/请求清单是数据不是工具，外部仓库默认不在冻结范围；不得把禁令外推到未声明的范围来跳过必做工作
- checkpoint 不是 publish：长运行批次需要保存进度时调用 commit-review checkpoint mode；准备 push/MR/交付时再调用 publish mode 对累计 diff 做完整评审
- 不要在普通执行轮次修改 goal-contract.md 来降低完成难度；需求完成、阶段切换或目标变更必须有 owner/完成证据
- 遵循 AGENTS.md 中的模式（命名、结构）
- 如发现新的陷阱 → 在步骤 8 中记录
- 如需要偏离计划 → 记录原因

### 步骤 6: 完整验证

所有变更完成后：
1. 运行 `python scripts/validate_workflow.py`
2. 运行 `python scripts/validate_harness_sync.py`
3. 如当前任务绑定 goal-contract.md，运行 `python scripts/lint_goal_contract.py --file {goal-contract-path}`；否则运行 `python scripts/lint_goal_contract.py` 确认现有目标契约未漂移
4. 运行 `python scripts/validate.py`（完整流程）
5. 如有任何步骤失败：
   a. 读取错误输出
   b. 修复问题
   c. 重新运行验证
   d. 重复直到通过（最多 3 次尝试）
6. 如 3 次尝试后仍失败 → 停止，记录问题，寻求帮助
7. 将最终验证写入 runtime validation；如果存在 acceptance/eval 结论，写入 runtime eval：
   - `python scripts/agent_runtime.py eval --id "{eval id}" --type "acceptance" --status pass|fail|skip --summary "{summary}"`

### 步骤 7: 同步 Harness Context

如本次修改涉及业务、架构、模块、构建、技能或流程：
1. 更新 `AGENTS.md`、`CONSTRAINTS.md`、`README.md`、`WORKFLOW.md`、`harness.yaml`、`.review/rules/` 中受影响的项。
2. 发现可机械化规则 → 写入脚本、`harness.yaml` 或 `.review/rules/`。
3. 不能确认的规则 → 写入 `hardening_debt` 或 owner action board。
4. 如果当前仓库是 harness 模板仓库，运行记录必须留在 `harness/.local/`，不得提交模板维护过程中的 performance、日期 trace 或历史遗留 `commit_log.csv`。
5. 重新运行 `python scripts/validate_harness_sync.py`。

### 步骤 8: 更新 BLACKBOARD

如任务关联到 BLACKBOARD 需求：
1. 更新需求状态
2. 更新“结果覆盖率”：计数名、旧值、新值、验证证据
3. 添加使用的技能和追踪引用
4. 更新变更日志；批量推进时每批一行、不超过 3 行文字（列单元名、计数变化、验证结论）

### 步骤 9: 写入 Memory 条目

对执行期间学到的每个新经验教训：
- 领域/API 陷阱 → 追加到 harness/memory/domain-api-gotchas.md
- 流程改进 → 追加到 harness/memory/process-improvements.md
- 工具/环境经验 → 追加到 harness/memory/tool-environment-lessons.md

格式：遵循每个 memory 文件中的模板。

### 步骤 10: 写入追踪条目

创建 `harness/trace/{YYYY-MM-DD}-{task-short-name}.md`（批量会话只建一个 trace 文件，按批追加小节，不每批新建），包含：
- 目标（Objective）
- 执行的步骤（Steps executed）（含结果）
- 作出的决策（Decisions made）（含理由）
- 验证结果（Validation results）
- 变更的文件（Files changed）
- 学到的经验（Lessons learned）（含对 memory 条目的交叉引用）

如果存在 agent runtime，优先补齐 run 目录中的：
- `workpad.json`
- `workpad.md`
- `result.md`
- `handoff.json`
- `handoff.md`
- `validation.json`
- `eval.json`

然后运行：

```bash
python scripts/agent_runtime.py workpad --complete plan-updated --complete acceptance-recorded --complete required-validation --complete runtime-validation --complete handoff-updated
python scripts/agent_runtime.py handoff --status needs_review --kind implementation-to-review --summary "{what changed}" --done "{completed work}" --next "{review or follow-up}"
python scripts/agent_runtime.py complete --status completed
python scripts/agent_runtime.py export-trace
```

如果任务未完成、中断或需要交给另一个 agent，不要只在回复里说明；先运行：

```bash
python scripts/agent_runtime.py pause --summary "{current state}" --done "{completed work}" --next "{exact next step}"
```

### 步骤 11: 报告完成

总结：
- 完成的内容
- 哪个结果计数移动了、旧值、新值、验证证据；如果没有移动，说明 blocker
- 变更的文件（列表）
- 验证状态（全部通过 / 警告）
- 新增的 memory 条目
- BLACKBOARD 更新
- 剩余工作或后续跟进

## 检查点协议（大型任务）

每更改 3-5 个文件：
1. 将当前进度保存到追踪文件（追加）
2. 运行 `python scripts/validate.py`
3. 如验证失败 → 在继续前修复
4. 如适用，更新 BLACKBOARD

## 错误处理

- 构建失败 → 读取错误，修复，重试（最多 3 次）
- 测试失败 → 判断是新失败还是已有失败
  - 新失败 → 立即修复
  - 已有失败 → 在追踪中记录，继续
- Lint 违规 → 立即修复（这些是机械规则）
- 连续 3 次失败后 → 停止，回滚变更，记录，寻求帮助

## 完成标准

- [ ] 所有计划的变更已实现
- [ ] 如当前任务绑定 goal-contract.md，本轮结果计数变化已写入 BLACKBOARD 和 handoff；无变化时已记录 blocker
- [ ] 完整验证流程通过
- [ ] `validate_workflow.py` 通过
- [ ] `validate_harness_sync.py` 通过
- [ ] agent runtime 已包含 workpad、validation/eval evidence、result.md 和 handoff.md（如果脚本存在；Completion Bar 未完成只作为提醒）
- [ ] 业务/流程变更已同步 harness context，或已进入 owner action
- [ ] 追踪条目已写入
- [ ] Memory 条目已添加（如有）
- [ ] BLACKBOARD 已更新（如适用）
- [ ] 没有处于不一致状态的未提交工作

## 输出产物

- **已实现的代码变更**：按计划创建、修改或删除的文件
- **追踪条目**：`harness/trace/{YYYY-MM-DD}-{task-short-name}.md` 记录目标、步骤、决策、验证结果、变更文件、经验教训
- **Runtime handoff**：`harness/.local/agent-runtime/runs/{run_id}/workpad.md`、`result.md`、`handoff.json` 和 `handoff.md`，用于本地恢复或未来 worker 接管
- **Memory 条目**（如有新经验）：追加到 harness/memory/domain-api-gotchas.md、harness/memory/process-improvements.md 或 harness/memory/tool-environment-lessons.md
- **BLACKBOARD 更新**（如适用）：结果覆盖率、需求状态、技能引用、变更日志
