---
name: project-init
description: 从零初始化新项目——从需求到可构建的骨架和 harness 基础设施。支持单平台和多平台 mono-repo 项目（web + iOS + Android 等）。当用户提到"初始化项目"、"新建项目"、"project init"、"空项目启动"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: Project Init

> 从零初始化新项目——从需求到可构建的骨架和 harness 基础设施。支持单平台和多平台 mono-repo 项目。

## 何时使用
- 全新项目，无源代码
- 只有 PRD、UI _mockups 或 UX 规格
- 由 harness-creator 自动调用（得分为 0-20 且未检测到源代码时）
- 需要 mono-repo 结构的多平台项目（如 web + iOS + Android 共用一个仓库）

## 前置条件
- PRD 或需求文档已提供（在 doc/features/{name}/src/ 或用户提供的路径）
- 目标目录为空或只包含需求/文档
- harness-creator skill 可用（在最后调用）

## 工作流程

### 步骤 1: 收集需求
- 阅读所有需求文档：PRD、UI 规格、UX 流程
- 如果结构已存在，查看 doc/features/{feature}/src/，或接受用户提供的路径
- 提取以下内容：
  - **项目类型**：web 应用、移动应用、API 服务、CLI 工具、桌面应用、库/SDK
  - **目标平台**：browser、iOS、Android、HarmonyOS、跨平台、服务器、桌面——**可多个**
  - **提到的核心技术**：需求中明确的任何技术偏好
  - **UI/UX 需求**：设计系统、响应式需求、无障碍级别、交互模式
  - **集成点**：API、数据库、第三方服务、认证提供商
  - **多平台策略**：共享后端？共享 UI 层？带共享逻辑的平台特定 UI？

### 步骤 1.5: 结构 Preview 前第一性原理反思

在向用户 preview 项目结构、技术栈或多平台策略前，先复核：
- 这个项目最先必须交付的用户结果是什么。
- 哪些约束不可违背：目标平台、需求中的技术偏好、团队可维护性、源码根必须在 `workspace/` 下。
- 最小可启动骨架是什么，哪些平台、共享包或 CI 能延后。
- 当前结构建议基于哪些需求证据，哪些是默认推断。
- 如果按当前结构启动，最可能造成的长期成本是什么；据此调整 preview 方案。

如存在 runtime，记录到 workpad：

```bash
python scripts/agent_runtime.py workpad --reflection "Project init preview reflection: outcome={...}; constraints={...}; smallest scaffold={...}; evidence={...}; risks={...}; structure adjustment={...}"
```

### 步骤 2: 确定项目结构
所有新项目必须先确定统一业务源码根目录：

- 默认源码根：`workspace/`
- 单平台默认应用目录：`workspace/apps/{platform}`
- 多平台默认应用目录：`workspace/apps/{platform}`，共享代码放 `workspace/packages/{name}`
- 不要把业务脚手架直接生成到仓库根；根目录保留给 Harness 基础设施、项目入口文档和仓库级配置。

根据目标平台数量：

**单平台** → 使用 `workspace/apps/{platform}` 作为项目骨架根，进入步骤 3。

**多平台（2+ 目标）** → 确定 mono-repo 策略：

| 策略 | 使用时机 | 结构 |
|----------|------------|-----------|
| **Shared-core mono-repo** | 平台共享业务逻辑但 UI 分离 | `workspace/packages/core/` + `workspace/apps/web/` + `workspace/apps/mobile/` + ... |
| **Cross-platform framework** | 一个代码库面向多个平台（Flutter、RN、KMP） | `workspace/apps/{app}/` 下的单一项目根目录带平台特定构建配置 |
| **Workspace mono-repo** | 独立应用共享库 | `workspace/` 内的 workspace（npm/pnpm workspaces、Gradle multi-module、Swift Package） |

创建平台矩阵：
| Platform | Framework | Package/Module Path | Build Command | Test Command |
|----------|-----------|-------------------|---------------|-------------|
| {platform} | {framework} | {path} | {cmd} | {cmd} |

**在继续之前等待用户确认结构。**

### 步骤 3: 选择技术栈
为每个平台展示推荐的技术栈。参考下方的**支持的技术栈**表格。

对于每个平台，指定：
- 语言 + 框架
- 构建工具
- 测试框架
- Linter + 格式化工具
- 包管理器

对于多平台项目，还需指定：
- 共享代码语言（TypeScript、Kotlin、Dart 等）
- Mono-repo 工具（Turborepo、Nx、Gradle multi-module、melos 等）
- 跨平台通信层（如需要）

**在继续之前等待用户确认。**

### 步骤 4: 初始化项目骨架

**单平台**：在 `workspace/apps/{platform}` 下运行相应的脚手架命令。

**多平台 mono-repo**：
1. 初始化 `workspace/` 根 workspace：
   ```
   # npm/pnpm workspaces, Gradle multi-module, melos, etc.
   ```
2. 在指定路径中脚手架每个平台模块：
   ```
   # e.g., workspace/apps/web → npx create-next-app
   #       workspace/apps/android → gradle init
   #       workspace/apps/ios → swift package init
   #       workspace/apps/harmony → deveco-studio project
   ```
3. 如适用，创建共享模块（`workspace/packages/core/`、`workspace/packages/shared/`）
4. 配置 workspace 级依赖和脚本

脚手架后（每个平台）：
1. 验证项目目录结构已创建
2. 在平台目录内创建任何额外的标准目录：`src/`、`tests/`、`docs/`
3. 运行初始构建以验证脚手架工作正常

### 步骤 5: 配置开发环境
生成或验证这些配置文件：

| File | Purpose |
|------|---------|
| .editorconfig | 一致的编辑器设置（缩进、字符集、EOL） |
| .gitignore | 仓库中所有平台的组合忽略规则 |
| Linter config | 每个模块的平台特定 linter 配置 |
| Formatter config | 每个模块的平台特定格式化配置 |
| .nvmrc / .python-version / .tool-versions | 运行时版本锁定 |

对于 mono-repo，还需配置：
- 根级 `package.json` / `settings.gradle` / `melos.yaml` / `Cargo.toml` workspace
- 构建/测试所有平台的共享 CI 工作流
- 需要的平台特定 CI 任务

如果需要 CI，创建 stub 工作流：
- `.github/workflows/ci.yml` — 所有平台的矩阵构建

### 步骤 6: 调用 Harness Creator
调用 `harness-creator` skill 来脚手架所有 harness 基础设施：
- 基于基础模板的 `harness.example.yaml` 生成目标项目真实 `harness.yaml`
- 如首个 PRD/MVP 属于长周期或易漂移需求，基于 `templates/goal-contract-template.md` 生成 `doc/features/{feature}/outputs/goal-contract.md`，默认 `project_kind: greenfield`
- 从 PRD 提取首批核心用户路径、验收标准、真实集成、mock/stub 剩余和 P0 阻塞计数，并在 `BLACKBOARD.md` 引用该 feature 目标契约
- 基于模板根目录 `WORKFLOW.md` 生成目标项目执行契约
- 用检测到的项目信息填充 AGENTS.md（包括平台矩阵）
- 用每个平台不同的规则填充 CONSTRAINTS.md
- 配置每个平台的构建/测试/lint 命令的 harness.yaml：
  ```yaml
  platforms:
    web:
      build: "npm run build"
      test: "npm test"
      path: "workspace/apps/web"
    android:
      build: "gradle assembleDebug"
      test: "gradle test"
      path: "workspace/apps/android"
    # ...
  ```
- scripts/ 验证流水线（按平台或全部运行）
- harness/memory/ + harness/trace/
- 本地机械层：`.githooks/`、`scripts/install_git_hooks.py`、`scripts/validate_workflow.py`、`scripts/validate_harness_sync.py`、`scripts/workspace_doctor.py`
- 首轮硬化产物：基于 `templates/first-hardening-template.md` 和 `templates/owner-action-board-template.md` 生成初始记录

### 步骤 7: 初始化 doc/ 模块

根据项目上下文确定 doc 策略：

**检测逻辑**（按顺序运行）：
1. 检查 `doc/` 是否存在且是 git submodule（`git submodule status` 显示 doc/）
   → **已是 submodule**：mode=submodule，跳过创建 doc 结构
2. 检查用户提供现有 doc git 仓库 URL
   → **链接现有 doc 仓库**：运行 `git submodule add {url} doc`，mode=submodule
3. 检查 `doc/` 作为常规目录存在且有内容
   → **现有本地 doc**：mode=local，保留现有内容
4. 以上都不是
   → **全新 doc**：创建 `doc/` 目录，mode=local

**基于检测到的模式**：

#### mode=local（新的 doc，此仓库拥有它）
1. 创建 `doc/features/{feature-name}/` 及子目录：`src/`、`intermediates/`、`generated/`、`outputs/`
2. 将原始需求文档复制到 `src/`
3. 从模板初始化 intermediates
4. 此 doc/ 目录后续可提取为独立的 git 仓库并作为 submodule 与其他平台仓库共享

#### mode=submodule（来自另一个仓库的共享 doc）
1. 验证 submodule 已初始化：`git submodule update --init doc`
2. 检查 `doc/features/{feature-name}/outputs/` 存在且有内容
3. **不要写入 doc/**——它是共享的只读 submodule
4. 创建 `harness/doc-local/{feature-name}/` 及子目录：`intermediates/`、`generated/`
   - 这是此仓库 review notes 和草稿的本地写入目录
5. 如果 outputs 已存在（functional-spec.md 等），记录："Existing doc outputs detected — prd-review can be skipped"

**写入 harness.yaml**：
```yaml
doc:
  path: "doc"              # relative path to doc directory
  mode: "local"            # local | submodule
  # submodule mode: writes go to harness/doc-local/ instead of doc/
harness_framework:
  schema_version: 1
  template_version: "{template-version-from-harness/VERSION}"
  source: "{template-remote-git-url}"
  source_ref: "{template-branch-or-tag}"
  last_upgrade: null
```

提交态 `harness.yaml` 的路径规则：

```text
harness_framework.source 必须是该项目实际使用的模板远程 Git URL，不能是开发者本机路径、WSL 路径、目标项目路径、"." 或其他项目的模板仓地址
harness_framework.source_ref 不得使用 "local"
其他路径字段必须使用仓库相对路径；本地绝对路径只允许写入 ignored 的 harness.local.yaml、local.properties 或命令行参数
```

如果不能确认该项目对应的模板远程，草稿中保留 `{HARNESS_TEMPLATE_REMOTE_URL}` 并记录 OWNER-ACTION，不要自动套用当前机器上任意模板仓的 `origin`；提交前必须由项目 owner 填入实际远程，否则 `lint_harness_config.py` 会阻断。

### 步骤 8: 生成按需目标契约和初始 BLACKBOARD.md

若首个 PRD/MVP 是长周期或易漂移需求，生成 `doc/features/{feature-name}/outputs/goal-contract.md`：
- 唯一目标使用 PRD 中的第一阶段用户结果，不写成“完成架构/搭好框架”
- 终态判定必须是可勾选 checklist
- 结果计数默认包含：核心用户路径 X/N、验收标准 X/N、真实集成 X/N、Mock/Stub 剩余 X/N、P0 阻塞项
- 固定循环使用“选一个用户路径 → 实现纵向切片 → 接真实依赖 → 测试/预览 → 更新计数”
- 硬性禁止明确：纯 scaffold、planner、auditor、证据包、基础设施整理不能替代用户路径推进
- 在 `doc/features/{feature-name}/outputs/product-spec-review.md` 或 `BLACKBOARD.md` 中链接该目标契约

短期一次性初始化或小型 bugfix 不生成目标契约，只保留普通 BLACKBOARD 条目和验证证据。

解析需求并创建初始条目：
- 每个功能需求或功能区域一个条目
- 对于多平台：用目标平台标记条目 `[web]` `[ios]` `[android]` `[harmony]` `[all]`
- 状态：`[ ] Not Started`
- 优先级：如果指定了则从需求推导，否则为 `Medium`
- 在 `BLACKBOARD.md` 顶部生成“结果覆盖率”表；如存在 feature 目标契约，计数必须与该契约保持一致，并在需求行“目标契约”列引用路径

同时生成或保留根目录 `WORKFLOW.md`：
- 新项目缺失时，从模板根目录 `WORKFLOW.md` 复制并按项目名/owner 做最小替换
- 已存在时，不覆盖；检查是否包含 `version`、`task_types`、`validation_profiles`
- 运行 `python scripts/validate_workflow.py`，失败时修复结构或记录 owner action

### 步骤 9: 验证
1. 运行 `python scripts/validate_workflow.py`
2. 运行 `python scripts/validate.py --dry-run`
3. 验证每个平台的脚手架/hello-world 代码可构建
4. 验证每个平台的测试框架可运行（即使零测试）
5. 对于 mono-repo：验证 workspace 级命令工作正常
6. 安装本地 hooks：
   - 有 git 仓库但无远程：`python scripts/install_git_hooks.py --mode install --profile bootstrap`
   - 有 git 仓库且已配置远程：`python scripts/install_git_hooks.py --mode install --profile baseline`
7. 运行 `python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace`
8. 报告任何配置问题

### 步骤 10: 生成初始化报告
写入追踪条目到 `harness/trace/{date}-project-init.md`：
- 每个平台选择的技术栈（带理由）
- Mono-repo 策略（如果是多平台）
- 平台矩阵
- 创建的文件和目录（数量 + 列表）
- 什么已准备好开发
- 什么需要人工输入（用 {PLACEHOLDER} 标记）
- 本地 hooks 安装状态和 workspace-doctor 输出摘要
- 推荐的下一个 skill：`prd-review`（如果尚未运行）或 `prd-to-design` / `ref-to-design`

## 支持的技术栈

### Web

| Stack | Scaffold Command | Build | Test | Linter |
|-------|-----------------|-------|------|--------|
| Next.js (React) | `npx create-next-app@latest` | `npm run build` | `jest` / `vitest` | `eslint` + `prettier` |
| Nuxt (Vue) | `npx nuxi init` | `npm run build` | `vitest` | `eslint` + `prettier` |
| Svelte / SvelteKit | `npx sv create` | `npm run build` | `vitest` | `eslint` + `prettier` |

### Mobile — Cross-Platform

| Stack | Scaffold Command | Build | Test | Linter |
|-------|-----------------|-------|------|--------|
| Flutter | `flutter create` | `flutter build` | `flutter test` | `flutter analyze` |
| React Native | `npx react-native init` | `npm run build` | `jest` | `eslint` + `prettier` |
| Kotlin Multiplatform | `gradle init` (KMP template) | `gradle build` | `gradle test` | `ktlint` / `detekt` |

### Mobile — Platform-Native

| Stack | Scaffold Command | Build | Test | Linter |
|-------|-----------------|-------|------|--------|
| Android (Kotlin) | `gradle init` / Android Studio template | `gradle assembleDebug` | `gradle test` | `ktlint` / `detekt` |
| Android (Java) | `gradle init` / Android Studio template | `gradle assembleDebug` | `gradle test` | `checkstyle` |
| iOS (Swift) | `swift package init` / Xcode template | `xcodebuild` | `xcodebuild test` | `swiftlint` |
| iOS (SwiftUI) | Xcode template | `xcodebuild` | `xcodebuild test` | `swiftlint` |
| HarmonyOS (ArkTS) | DevEco Studio template | `hvigorw assembleHap` | `hvigorw test` | DevEco built-in |
| HarmonyOS (ArkUI) | DevEco Studio template | `hvigorw assembleHap` | `hvigorw test` | DevEco built-in |

### Backend / API

| Stack | Scaffold Command | Build | Test | Linter |
|-------|-----------------|-------|------|--------|
| Python / FastAPI | `mkdir + pyproject.toml` | `python -m build` | `pytest` | `ruff` |
| Java / Spring Boot | `spring init` / `mvn archetype:generate` | `mvn compile` | `mvn test` | `checkstyle` |
| Go | `go mod init` | `go build ./...` | `go test ./...` | `golangci-lint` |
| Rust | `cargo init` | `cargo build` | `cargo test` | `clippy` |
| .NET / C# | `dotnet new webapi` | `dotnet build` | `dotnet test` | `dotnet format` |
| Node.js / Express | `npm init + express-generator` | `npm run build` | `jest` / `vitest` | `eslint` + `prettier` |

### Mono-Repo Tooling

| Tool | Ecosystem | Use Case |
|------|-----------|----------|
| Turborepo / Nx | JavaScript/TypeScript | Web + Node mono-repos |
| pnpm workspaces | JavaScript/TypeScript | Lightweight workspace management |
| Gradle multi-module | JVM (Kotlin/Java) | Android + backend + KMP |
| melos | Dart/Flutter | Flutter multi-package repos |
| Cargo workspaces | Rust | Multi-crate repos |
| Swift Package Manager | Swift/iOS | iOS multi-module repos |

## 完成标准
- [ ] 项目骨架构建成功（多平台则所有平台）
- [ ] 每个平台配置了开发环境（linter、formatter、gitignore）
- [ ] 脚手架了 harness 基础设施（通过 harness-creator）
- [ ] 已从 harness.example.yaml 生成或更新目标项目 harness.yaml
- [ ] WORKFLOW.md 已生成或保留，并通过 `validate_workflow.py`
- [ ] 配置了 mono-repo workspace（如果是多平台）
- [ ] 初始化了 doc/（local：seeding 结构；submodule：链接 + harness/doc-local/ 已创建）
- [ ] harness.yaml 包含正确的 `doc.path` 和 `doc.mode`
- [ ] 长周期/易漂移首个需求已生成 feature `goal-contract.md`，或已明确无需目标契约
- [ ] BLACKBOARD.md 填充了初始条目（多平台则带平台标签）
- [ ] BLACKBOARD.md “结果覆盖率”与已绑定 feature 目标契约计数一致（如存在）
- [ ] 每个平台运行验证流水线（dry-run 通过）
- [ ] 本地 hooks 已安装，或安装失败原因已写入 owner action board / hardening_debt
- [ ] workspace-doctor 已运行并写入 trace
- [ ] 创建了初始化报告追踪条目

## 输出产物
- 已初始化的项目骨架（每个平台）
- 开发配置文件（.editorconfig、.gitignore、linter、formatter）
- Mono-repo workspace 配置（如果是多平台）
- Harness 基础设施（通过 harness-creator）
- doc/ 设置（local：doc/features/{name}/ 已 seeding；submodule：已链接 + harness/doc-local/ 已创建）
- WORKFLOW.md（仓库级执行契约）
- doc/features/{feature-name}/outputs/goal-contract.md（按需，唯一目标、终态判定、结果计数和反 meta 规则）
- BLACKBOARD.md 及初始条目
- 首轮硬化报告和 owner action board 初始版本
- 追踪条目：harness/trace/{date}-project-init.md

## 错误处理
- 如果脚手架命令失败 → 检查网络、检查 CLI 工具是否安装、用安装说明报告
- 如果选中的技术栈 CLI 不可用 → 提供手动设置说明作为后备
- 如果脚手架后构建失败 → 可能是环境问题、用诊断步骤报告
- 如果 harness-creator 失败 → 继续提供手动 harness 设置说明
- 如果 mono-repo 中一个平台失败 → 继续其他平台、单独报告失败的平台
- 永远不要留下半初始化的项目——如果关键失败、清理创建的文件并报告

(End of file - total 280 lines)
