---
name: harness-creator
description: 审计项目当前状态并搭建缺失的 harness 基础设施。新项目接入 harness 框架、现有项目缺失 harness 组件、或空项目启动时使用。当用户提到"接入 harness"、"搭建 harness"、"初始化 harness"、"harness creator"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: Harness Creator

> 审计项目当前状态，搭建缺失的 harness 基础设施。

## 何时使用
- 新项目没有 harness 基础设施
- 已有项目缺少 harness 组件
- 重大架构重组导致现有 harness 失效
- 全新的空项目（先委托 project-init skill 处理）

## 前置条件
- 可访问项目根目录
- 项目中已复制/包含此 harness 框架
- 有 project-init skill 可用（用于空项目）

## 工作流程

### 步骤 1: 审计项目结构
- 扫描项目根目录的现有文件：AGENTS.md、CONSTRAINTS.md、BLACKBOARD.md、WORKFLOW.md、harness.yaml、harness.example.yaml、harness/、scripts/、.agents/
- 如果存在 `doc/features/**/outputs/goal-contract.md`，读取对应 feature/workstream 目标契约和结果计数口径
- 扫描构建系统标识：package.json、pom.xml、build.gradle、Cargo.toml、go.mod、Makefile、pyproject.toml 等
- 识别主要语言和框架
- 检查现有测试基础设施
- 检查现有 linter/formatter 配置
- **检测 doc/ 状态**：运行 `git submodule status` — 如果 doc/ 显示为子模块，记录 `doc.mode: submodule`；否则 `doc.mode: local`

### 步骤 2: 评分当前状态
对项目的 harness 准备度进行评分（0-100，超过按 100 计）：
- AGENTS.md 存在且已填充：+15
- CONSTRAINTS.md 存在且已填充：+15
- 存在 feature/workstream `goal-contract.md` 且有终态 checklist / 结果计数：+5（仅在已有长周期需求时加分）
- BLACKBOARD.md 存在：+5
- WORKFLOW.md 存在且 `validate_workflow.py` 通过：+5
- harness/memory/ 存在且有文件：+10
- harness/trace/ 存在：+5
- scripts/ 验证流水线存在：+15
- .agents/skills/ 存在且有 skill：+15
- 构建系统已配置：+10
- 测试存在：+10

输出评分和详细分项。

### 步骤 3: 确定行动计划
根据评分：
- **0-20（空白）**：全量脚手架 — 从模板创建一切
  - 如果评分为 0-20 且未检测到源代码文件（无 `workspace/`、`src/`、`lib/`、`app/` 或语言特定的源文件），这可能是全新项目。先调用 `project-init` skill 来搭建项目骨架和技术栈，然后返回 harness-creator 步骤 4 在其上搭建 harness 基础设施。
- **21-50（部分）**：补全缺口 — 只创建缺失的组件
- **51-70（基本就绪）**：完善 — 修复不一致性，更新过时内容
- **71-100（已就绪）**：验证 — 运行验证，报告状态

### 步骤 3.5: 检查空项目（当评分为 0-20 时）
如果评分为 0-20：
1. 扫描任何源代码文件：*.py、*.js、*.ts、*.java、*.go、*.rs、*.rb、*.php 等
2. 检查是否存在 `workspace/`、`src/`、`lib/`、`app/` 或其他语言特定目录
3. 如果没有源代码 → 这是全新的空项目：
   - 委托 `project-init` skill 来搭建项目骨架和技术栈
   - 要求 project-init 将业务骨架放入 `workspace/apps/{platform}` 或 `workspace/packages/{name}`，不要直接写到仓库根
   - 等待 project-init 完成并确认项目结构已就绪
   - 返回步骤 4 在新创建的项目上搭建 harness 基础设施
4. 如果源代码存在但缺少 harness → 按正常步骤 4 进行脚手架搭建

### 步骤 4: 搭建缺失的基础设施
对每个缺失的组件：
1. 从本框架复制模板
2. 自动填充可检测的内容（语言、构建工具、目录结构）
3. 用 {PLACEHOLDER} 标记需要人工输入的内容

优先级顺序：
1. AGENTS.md（agent 首先需要这个）
2. CONSTRAINTS.md（规则先于代码）
3. harness.yaml（目标项目脚本需要配置；基础模板可先提供 harness.example.yaml）
4. WORKFLOW.md（仓库级执行契约）
5. scripts/（验证流水线）
6. harness/memory/ + harness/trace/（知识基础设施）
7. BLACKBOARD.md（需求跟踪和结果覆盖率）
8. feature/workstream goal-contract.md（仅当已有长周期需求或迁移 workstream 需要结果计数约束）
9. .agents/skills/（工作流 skill）
10. .githooks/ + hooks 安装脚本（本地机械门禁）
11. workspace-doctor（新人/回接手环境诊断）

### 步骤 5: 配置 harness.yaml
自动检测并生成目标项目的 harness.yaml。若当前仓库是基础模板且尚未初始化业务项目，则保留或生成 harness.example.yaml，等待 project-init 在目标项目中落地真实 harness.yaml。

#### 占位符填充规则

**project.name**
- 优先从 package manifest 读取：package.json 的 name、pyproject.toml 的 [project].name、Cargo.toml 的 [package].name 等
- 无 manifest 时使用目录名

**project.language**
- 从文件扩展名聚合 + 构建系统推断
- package.json → JavaScript/TypeScript
- pyproject.toml → Python
- go.mod → Go
- Cargo.toml → Rust
- pom.xml/build.gradle → Java/Kotlin

**project.platform**
- 从目录/配置推断：
  - android → 包含 AndroidManifest.xml
  - ios → 包含 *.xcodeproj
  - web → 含 package.json 且有前端框架
  - server → 含 backend 框架

**doc.path**
- 默认为 "doc"

**doc.mode**
- 从步骤 1 的 git submodule 检测结果填充：
  - `git submodule status` 显示 doc/ 为子模块 → "submodule"
  - 否则 → "local"

**feature/workstream goal contract**
- 不向 `harness.yaml` 写入 `goal` 或 `goal.contract`
- 若已有长周期需求、迁移 workstream 或 PRD 输出需要结果计数约束，从 `templates/goal-contract-template.md` 生成 `doc/features/{feature}/outputs/goal-contract.md`
- `project_kind`: 空项目/新项目需求为 `"greenfield"`；存量迁移或重构 workstream 为 `"migration"` 或 `"refactor"`；长周期稳定性/缺陷流为 `"maintenance"`；不能确认时保留 owner action
- 若证据不足，终态 checklist 和结果计数用 `{PLACEHOLDER}` 标明需负责人确认的项，并在 `BLACKBOARD.md` 需求行引用该目标契约

**harness_framework**
- `schema_version`: 继承 `harness.example.yaml`
- `template_version`: 读取当前模板 `harness/VERSION`；如缺失才回退到 `harness.example.yaml`
- `source`: 目标项目提交的 `harness.yaml` 必须填写该项目实际使用的模板远程 Git URL，用于后续 `harness-upgrade`；不得写入开发者本机路径、WSL 路径、目标项目路径、`"."` 或其他项目的模板仓地址
- `source_ref`: 填写模板分支或 tag，推荐固定到稳定分支/tag；提交态不得使用 `"local"`
- `last_upgrade`: 首次接入时可留空或填接入日期

本地模板路径只允许用于调试：

```text
python scripts/upgrade_harness.py --source {local-template-path} ...
```

或写入 ignored 的 `harness.local.yaml`，不得进入会提交的 `harness.yaml`。

如果不能确认该项目对应的模板远程，草稿中保留 `{HARNESS_TEMPLATE_REMOTE_URL}` 并记录 OWNER-ACTION，不要自动套用当前机器上任意模板仓的 `origin`；提交前必须由项目 owner 填入实际远程，否则 `lint_harness_config.py` 会阻断。

**platforms.*.build / platforms.*.test**
- 从构建系统映射：
  - package.json → npm run build、npm test
  - pom.xml → mvn compile、mvn test
  - build.gradle → gradle build、gradle test
  - Cargo.toml → cargo build、cargo test
  - go.mod → go build ./...、go test ./...
  - pyproject.toml → python -m build、pytest

**platforms.*.path**
- 新项目默认指向 `workspace/apps/{platform}`，共享库指向 `workspace/packages/{name}`
- 已有项目可保留实际路径；只有存量根目录项目才使用 "."
- 该路径是普通代码操作和验证扫描的业务根，不应指向 `harness/`、`.agent-harness/`、`.agents/` 或 `scripts/`

**architecture.layers**
- 从目录扫描推断（最佳努力原则）：
  - workspace/apps/{platform}/src/api → api 层，level=0
  - workspace/apps/{platform}/src/services → service 层，level=1
  - workspace/apps/{platform}/src/repositories → repository 层，level=2
  - 存量项目仍可使用 src/api、src/services、src/repositories 等实际路径
  - 等常见模式

**无法自动填充的字段**
- 保留 {PLACEHOLDER} 标记，列入步骤 8 的审计报告

### 步骤 6: 填充 AGENTS.md
填充检测到的信息：
- Overview：从 package manifest description 获取
- Architecture layers：从目录扫描获取
- Key directories：从项目结构获取
- Naming conventions：从现有文件名获取

### 步骤 7: 运行初始验证
先运行 `python scripts/validate_workflow.py` 验证 `WORKFLOW.md`，再执行 `python scripts/validate.py --dry-run` 验证流水线配置是否正确。
报告任何会失败的步骤及原因。

随后处理本地机械层：
1. 运行 `python scripts/install_git_hooks.py --mode status`
2. 未安装时，根据仓库状态选择 profile：
   - 无远程或首次接入：`bootstrap`
   - 已有远程且 baseline/基础规则可运行：`baseline`
3. 运行 `python scripts/install_git_hooks.py --mode install --profile {profile}`
4. 如果已有 `core.hooksPath` 冲突，不要强制覆盖；写入 `hardening_debt` 或 owner action board，等待负责人确认。
5. 运行 `python scripts/workspace_doctor.py --json`，把 SELF-FIX / OWNER-ACTION 摘要写入 trace。

### 步骤 8: 生成审计报告
向 `harness/trace/{date}-harness-creation.md` 写入追踪条目，包含：
- 审计评分和详细分项
- 创建/修改了什么
- 仍需人工输入的内容（列出所有 {PLACEHOLDER} 标记）
- hooks 安装状态、冲突和 owner action
- 建议的后续步骤

## 完成标准
- [ ] 所有 harness 基础设施文件存在
- [ ] 如存在长周期需求或迁移 workstream，feature `goal-contract.md` 已生成并通过 `python scripts/lint_goal_contract.py --file ...`；短任务缺失不算问题
- [ ] 目标项目 harness.yaml 已针对项目的语言/构建系统配置，或基础模板提供 harness.example.yaml
- [ ] WORKFLOW.md 存在并通过 `validate_workflow.py`，或缺失原因已进入 owner action
- [ ] AGENTS.md 已填充检测到的项目信息
- [ ] 验证流水线可运行（即使因项目设置缺失导致部分步骤失败）
- [ ] 本地 hooks 状态已确认；不能安装时已形成负责人待办
- [ ] workspace-doctor 可运行
- [ ] 审计报告追踪条目已创建
- [ ] 所有剩余 {PLACEHOLDER} 标记已列出供人工审查

## 输出产物
- harness.yaml：目标项目配置文件
- harness.example.yaml：基础模板配置示例
- AGENTS.md：agent 项目地图
- doc/features/{feature}/outputs/goal-contract.md：按需目标契约、终态判定和结果计数
- CONSTRAINTS.md：架构规则
- BLACKBOARD.md：需求跟踪看板
- WORKFLOW.md：仓库级执行契约
- scripts/：验证流水线
- harness/memory/：知识积累
- harness/trace/：执行追踪
- .agents/skills/：工作流技能
- 审计报告：harness/trace/{date}-harness-creation.md

## 错误处理
- 如果项目根目录不可写 → 终止并给出明确错误
- 如果未检测到构建系统 → 在基础模板中保留 harness.example.yaml；在目标项目中创建带占位符命令的 harness.yaml，提醒用户
- 如果未检测到语言 → 默认使用通用配置，提醒用户
- 未经 --force 标志确认，不得覆盖现有文件
