---
name: harness-onboard
description: >
  现有项目一键接入 harness 框架的端到端流水线 skill。串联 harness-audit、
  harness.yaml 生成、baseline 初始化、首次 validate、migration-roadmap 生成、
  渐进改造、strict 模式切换全流程。AI 全程代劳人工步骤，只在 3 个真决策点暂停
  等人类确认。当用户提到"一键接入 harness"、"现有项目接入"、"harness-onboard"、
  "场景 B 接入"、"一次性搞定接入"时激活此 Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
triggers:
  - "一键接入 harness"
  - "harness-onboard"
  - "现有项目接入 harness"
  - "场景 B 接入"
  - "一次性搞定接入"
  - "onboard"
preconditions:
  - project_path 指向非空的已有项目根目录
  - harness 模板已复制到 project_path（存在 scripts/、.agents/skills/、harness.example.yaml）
  - 目标项目尚未完成 harness 接入（无 harness.yaml 或存在但未通过首次 validate）
inputs:
  - project_path：被接入的现有项目根目录路径
outputs:
  - project_path/harness.yaml（最终项目配置）
  - project_path/doc/features/{project}/outputs/goal-contract.md（存量迁移/改造目标契约）
  - project_path/harness/baseline.json（历史债基线）
  - project_path/doc/features/{project}/outputs/harness-audit/{YYYY-MM-DD}-*.md（audit 三份报告）
  - project_path/doc/features/{project}/outputs/migration-roadmap.md（改造路线图）
  - project_path/harness/trace/{YYYY-MM-DD}-harness-onboard.md（执行追踪）
---

# Harness-Onboard Skill

> 现有项目接入 harness 的端到端 skill。把场景 B 原本需要人/AI 切换 5+ 次的 9 步流程，
> 合并为一条 AI 主导的流水线，只在 **3 个真决策点**暂停等人类确认。

## 设计目标

**场景 B 原流程**（人工步骤用 🧑，AI 步骤用 🤖）：
```
🧑 复制模板 → 🤖 harness-audit → 🧑 填写 harness.yaml → 🧑 baseline-init →
🧑 validate → 🤖 migration-roadmap → 🤖+🧑 渐进改造 → 🧑 回归安全网 → 🧑 切 strict
```
共 9 步、5+ 次人/AI 切换。

**本 skill 流程**（AI 主导，仅 3 次人类确认用 ✋）：
```
🤖 前置检查 → 🤖 harness-audit → 🤖 生成 harness.yaml 草稿
  → ✋ 确认 #1（harness.yaml） → 🤖 baseline-init + validate
  → 🤖 migration-roadmap → ✋ 确认 #2（roadmap 优先级）
  → 🤖 渐进改造（按 Phase 1）→ 🤖 回归测试补齐建议
  → ✋ 确认 #3（切 strict？）→ 🤖 切 strict + 最终 validate
```

## 何时使用

- 已有大量历史代码的现有项目首次接入 harness 框架
- 想一次性走完场景 B 全流程，不想手动在多个 skill 之间切换
- 用户明确表达"一键接入"、"场景 B 接入"意图

## 不触发条件

- 空项目或全新项目 → 使用 `project-init`
- 已完成接入（harness.yaml 存在且 validate 通过）→ 使用 `harness-executor`
- 仅需补全个别 harness 组件 → 使用 `harness-creator`
- 用户明确只想单独运行 audit 或 roadmap → 使用对应独立 skill

## 前置条件

| 条件 | 不满足时 |
|------|---------|
| project_path 存在且非空 | 报错：Project path not found or empty |
| 模板已复制（存在 scripts/validate.py） | 报错：Harness template not copied. Run copy step first. |
| 未完成接入 | 询问是否强制重新接入（可能覆盖 harness.yaml） |
| 目录可写 | 报错：Permission denied |

## 工作流程

### Phase 0：前置检查（AI 自动，无人类干预）

1. 验证 `project_path` 存在且非空
2. 验证 harness 模板已复制：检查 `scripts/validate.py`、`scripts/validate_workflow.py`、`.agents/skills/`、`harness.example.yaml`
3. 检查是否已有 `harness.yaml`：
   - 不存在 → 继续
   - 存在但 validate 未通过 → 继续（视为未完成接入）
   - 存在且 validate 通过 → 终止，建议用 `harness-executor`
4. 在 `harness/trace/{YYYY-MM-DD}-harness-onboard.md` 创建追踪文件，记录起始时间
5. 检查本地 hooks：
   - `python scripts/install_git_hooks.py --mode status`
   - 如果没有 hooksPath，先记录为待安装
   - 如果已有非 `.githooks` 的 hooksPath，记录 OWNER-ACTION，不强行覆盖

### Phase 1：自动审计（AI 调用 harness-audit）

调用 `harness-audit` skill，传入 `project_path`。等待产出：
- `doc/features/{project}/outputs/harness-audit/{YYYY-MM-DD}-current-state.md`
- `doc/features/{project}/outputs/harness-audit/{YYYY-MM-DD}-gap-analysis.md`
- `doc/features/{project}/outputs/harness-audit/{YYYY-MM-DD}-risk-assessment.md`
- 可选：`{YYYY-MM-DD}-initial-harness.yaml`

失败 → 写入 trace，终止并报错。

### Phase 2：生成 harness.yaml 草稿（AI 自动）

基于 audit 产出，从 `harness.example.yaml` 复制并填充：

| 字段 | 数据来源 |
|------|---------|
| `project.name` | audit 探测到的项目名 |
| `project.language` | audit 探测到的主语言 |
| `harness_framework.*` | 从 `harness/VERSION` 和模板来源生成当前模板版本与来源，用于后续 `harness-upgrade` |
| `platforms.*` | audit 探测到的构建系统 + 模块布局 |
| `validation.build.command` | 构建系统对应默认命令（Gradle → `./gradlew assembleDebug`，Maven → `mvn package` 等） |
| `validation.test.command` | 测试框架默认命令；无测试时 `validation.test.enabled: false` |
| `architecture.layers` | 基于 audit 模块布局**推断**；如无明显层级则留空 + 写注释 |
| `architecture.exclude_paths` | audit 推荐 + 标准：`**/build/**`、`**/.gradle/**`、`**/generated/**` |
| `architecture.generated_code_paths` | audit 推荐 |
| `quality.baseline_mode` | 强制 `"baseline"`（首次接入必须） |
| `quality.max_file_lines` / `max_function_lines` | 继承 harness.example.yaml 默认值 |
| `quality.exclude_paths` | audit 推荐 |

保存到 `project_path/harness.yaml`，并在缺失时从模板根目录生成 `project_path/WORKFLOW.md`。若目标项目已存在 `WORKFLOW.md`，不得覆盖；只运行 `python scripts/validate_workflow.py` 并把需要人工合并的任务类型、validation profile 或 human gate 写入确认材料。

同时从 `templates/goal-contract-template.md` 生成 `project_path/doc/features/{project}/outputs/goal-contract.md`：
- `project_kind` 默认为 `migration`
- 唯一目标写成存量系统迁移或改造的可交付结果
- 终态 checklist 来自 audit 和 migration-roadmap 的目标，不得写成“完成报告/完成评估”
- 结果计数至少包含：已迁移/已改造范围 X/N、旧依赖/bridge 剩余 X/N、验收/回放/回归通过 X/N、owner gates closed X/N
- 若 audit 证据不足，保留 `{PLACEHOLDER}` 并列入确认点 #1，不要编造计数分母
- 在 `BLACKBOARD.md` 迁移 workstream 行或 `product-spec-review.md` 的“目标契约”章节引用该文件

### ✋ 人类确认点 #1：harness.yaml 草稿

**为什么暂停**：架构 `layers` 是项目特有判断（哪些目录是 UI、哪些是 domain），AI 可能猜错；
`exclude_paths` 可能遗漏项目特有的生成目录。猜错会导致后续 validate 大量误报。

**呈现给用户**：
```
已生成 harness.yaml 草稿，请确认以下关键字段：

1. architecture.layers — AI 推断为 {X 层}，对应路径：
   - api: src/api/
   - service: src/services/
   （如果不对，请修改 harness.yaml 后告诉我）

2. architecture.exclude_paths — 已排除：
   - **/build/**, **/.gradle/**, **/generated/**
   （如有项目特有的 vendor/cache 目录需要补充，请修改）

3. quality.baseline_mode = "baseline"（强制，首次接入豁免历史债）

4. `doc/features/{project}/outputs/goal-contract.md` — 终态 checklist 和结果计数是否符合本次接入/迁移目标

Ready to proceed? [yes / modified]
```

用户回答 `yes` 或确认已修改 → 继续 Phase 3。
用户要求改动 → 写入 trace，让用户修改后再次触发本 skill 从 Phase 3 继续。

### Phase 3：baseline 初始化 + 首次 validate（AI 自动）

1. 运行 `python scripts/validate_workflow.py`
2. 运行 `python scripts/lint_quality.py --baseline-init` → 生成 `harness/baseline.json`
3. 运行 `python scripts/validate.py`
   - 退出码 0 → 继续
   - 退出码非 0 → 分析失败原因：
     - 如果是 `harness.yaml` 字段错误 → 修正 + 重试 1 次；仍失败 → 回到确认点 #1
     - 如果是架构违规（baseline 模式下不应出现）→ 检查 exclude_paths 是否遗漏 → 建议补充 + 回到确认点 #1
     - 如果是脚本错误 → 写入 trace 并终止
3. 安装本地 hooks：
   - 无远程或负责人尚未确认目标分支策略：`python scripts/install_git_hooks.py --mode install --profile bootstrap`
   - 已有远程且首次 validate 退出码为 0：`python scripts/install_git_hooks.py --mode install --profile baseline`
   - 已有 hooksPath 冲突时不加 `--force`，写入 OWNER-ACTION

### Phase 4：生成 migration-roadmap（AI 调用 migration-roadmap skill）

调用 `migration-roadmap` skill，消费 Phase 1 的 audit 产出。等待产出：
`doc/features/{project}/outputs/migration-roadmap.md`（含 3-phase 任务清单，S/M/L 分级）

### ✋ 人类确认点 #2：migration-roadmap 优先级

**为什么暂停**：roadmap 的优先级涉及团队战略（哪个模块更重要、哪些 God 文件影响业务功能、
哪些可以延后）。AI 只能按机械指标（行数、依赖深度）排序，必须人类裁决。

**呈现给用户**：
```
已生成 migration-roadmap.md，包含 {N} 项任务：
- Phase 1（S 级，本周内可完成）：{X} 项
- Phase 2（M 级，1-3 周）：{Y} 项
- Phase 3（L 级，需安全网）：{Z} 项

Top 3 推荐任务（按行数 + 风险）：
1. ProtocolRequestId.java (1885 行) — L
2. BizMonitor.java (1544 行) — L
3. RouterConfig.kt (912 行) — M

要调整优先级吗？或者直接开始执行 Phase 1（S 级任务）？
[execute-phase-1 / adjust / skip-refactor]
```

- `execute-phase-1` → 继续 Phase 5
- `adjust` → 终止，等用户修改 roadmap 后重新触发
- `skip-refactor` → 跳到 Phase 6（仅完成基础接入）

### Phase 5：执行 Phase 1 任务（AI 自动，逐任务）

对 roadmap Phase 1 的每个 S 级任务：
1. 读取任务描述和目标文件
2. 委派给 `harness-executor` skill 完成
3. 每个任务完成后运行 `validate.py` 确认未引入新违规
4. 任务失败 → 记录到 trace，跳过并继续下一个（不中断流程）

全部完成后，运行 `lint_quality.py --baseline-init` 刷新 baseline（反映已解决的债务）。

### Phase 6：回归测试补齐建议（AI 自动生成建议，不执行）

扫描项目测试覆盖现状，生成建议清单到 trace：
- 已有测试的模块 → 跳过
- 无测试的核心业务模块 → 列出建议补齐的测试文件
- **不实际写测试**（测试补齐是长期工作，不在 onboard 范围）

### ✋ 人类确认点 #3：切换 strict 模式？

**为什么暂停**：`baseline_mode: strict` 改变 CI 行为（零容忍历史债），
影响团队所有成员的提交门禁。必须人类决策团队是否 ready。

**呈现给用户**：
```
接入基础流程已完成：
- ✅ harness.yaml 已配置
- ✅ baseline.json 已建立（N 条历史违规）
- ✅ validate.py 首次退出码 0
- ✅ migration-roadmap.md 已生成
- ✅ Phase 1 完成 {X}/{Y} 个 S 级任务
- ✅ 本地 hooks 状态：installed | owner-action | skipped
- ⚠️ 建议回归测试：{K} 个核心模块缺测试

现在是否切换到 strict 模式（零容忍新增违规）？
- yes：切换 + 最终 validate
- no：保持 baseline 模式（推荐——先完成 Phase 2/3 改造再切）

[yes / no]
```

- `yes` → 继续 Phase 7
- `no` → 跳到 Phase 8（完成）

### Phase 7：切换 strict 模式（AI 自动）

1. 修改 `harness.yaml` 的 `quality.baseline_mode: strict`
2. 运行 `python scripts/validate.py` 最终确认
3. 失败 → 回滚到 `baseline` + 记录失败原因到 trace + 提示用户

### Phase 8：完成 + 写入 trace

在 `harness/trace/{YYYY-MM-DD}-harness-onboard.md` 写入完整追踪：
- 起止时间
- 每个 Phase 的产出和耗时
- 3 个确认点的用户选择
- 最终 `baseline_mode` 和 baseline 违规数量
- 本地 hooks profile 和未解决冲突
- 未完成项（Phase 2/3 任务、测试补齐建议）

打印完成摘要给用户。

## 产物格式

### 最终产物清单

```
{project_path}/
├── harness.yaml                                              # 项目配置
├── WORKFLOW.md                                               # 仓库级执行契约
├── harness/
│   ├── baseline.json                                         # 历史债基线
│   └── trace/{YYYY-MM-DD}-harness-onboard.md                 # 执行追踪
├── doc/features/{project}/outputs/
│   ├── harness-audit/{YYYY-MM-DD}-*.md                       # audit 三份
│   ├── goal-contract.md                                      # 迁移目标契约和结果计数
│   └── migration-roadmap.md                                  # 改造路线图
```

### Trace 模板结构

```markdown
# Harness Onboard Trace: {project}

- Start: {YYYY-MM-DD HH:MM}
- End: {YYYY-MM-DD HH:MM}
- Final mode: baseline | strict

## Phase 0: Pre-checks
- [x] project_path valid
- [x] template copied

## Phase 1: Audit
- Reports: {links}

## Phase 2: harness.yaml draft
- Layers inferred: {...}
- Goal contract: doc/features/{project}/outputs/goal-contract.md
- Goal counters: {...}
- User confirmation #1: yes | modified

## Phase 3: baseline + validate
- Baseline violations: {N}
- Validate exit code: 0
- Hooks profile: bootstrap | baseline | owner-action

## Phase 4: Migration roadmap
- Tasks: S={X} M={Y} L={Z}

## Phase 5: Phase 1 execution
- Completed: {X}/{Y}
- Failed: [{task-id}: reason]

## Phase 6: Test coverage suggestions
- Gaps: {N} modules

## Phase 7: Strict switch
- User confirmation #3: yes | no
- Final validate: pass | fail

## Outstanding work
- Phase 2/3 refactor tasks
- Test coverage backlog
```

## 错误处理

| 错误 | 处理 |
|------|------|
| project_path 不存在 | 报错终止 |
| 模板未复制 | 报错 + 提示复制模板的命令 |
| harness-audit 失败 | 写 trace + 终止（不可恢复） |
| harness.yaml 字段错误 | 自动修正 + 重试 1 次；仍失败 → 回到确认点 #1 |
| baseline-init 失败 | 写 trace + 终止（脚本问题） |
| 首次 validate 非 0 | 分析原因 + 修正；仍失败 → 回到确认点 #1 |
| 既有 hooksPath 冲突 | 不覆盖，写 OWNER-ACTION，继续基础接入 |
| migration-roadmap 失败 | 写 trace + 跳到 Phase 8（基础接入仍算成功） |
| Phase 1 任务失败 | 单任务跳过，继续下一个，trace 记录 |
| strict 模式 validate 失败 | 自动回滚到 baseline + 通知用户 |

## 完成标准

- [ ] project_path 验证通过
- [ ] audit 三份报告已生成
- [ ] harness.yaml 已生成且通过用户确认
- [ ] `doc/features/{project}/outputs/goal-contract.md` 已生成且通过用户确认，`lint_goal_contract.py --file ...` 通过
- [ ] WORKFLOW.md 已生成或保留，并通过 `validate_workflow.py`
- [ ] baseline.json 已建立
- [ ] 首次 validate 退出码 0
- [ ] 本地 hooks 已安装或冲突已进入 OWNER-ACTION
- [ ] migration-roadmap.md 已生成
- [ ] 3 个确认点已记录用户选择
- [ ] Phase 1 任务已尝试执行（成功或失败均可）
- [ ] 最终状态（baseline 或 strict）已确认
- [ ] trace 已完整写入

## 与其他 skill 的关系

| Skill | 关系 |
|------|------|
| `harness-audit` | 被本 skill 调用（Phase 1） |
| `migration-roadmap` | 被本 skill 调用（Phase 4） |
| `harness-executor` | 被本 skill 调用（Phase 5 每个 refactor 任务） |
| `harness-creator` | 互斥：新项目/补全场景用 creator，现有项目接入用本 skill |
| `project-init` | 互斥：空项目用 init，现有项目用本 skill |
| `commit-review` | 下游：本 skill 完成后用户自行触发提交 |
