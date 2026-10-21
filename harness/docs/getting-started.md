# 快速入门

## 前置条件

您的目标项目应该具备或即将通过 `project-init` 创建：
- 构建系统（npm、cargo、maven 等）
- 测试框架
- Git 版本控制
- Python 可执行命令（`python` 或 `python3`）
- Android 项目额外要求 Android CLI（`android` 命令可用）

## 设置清单

### 步骤 1：复制框架

将基础模板复制到项目根目录。初始化前允许只有 harness 基础设施和需求文档；初始化后项目结构通常如下：

```
my-project/
├── .agents/
├── doc/
├── harness/
├── harness.yaml
├── WORKFLOW.md
├── .githooks/
├── workspace/
│   └── apps/{platform}/
│       ├── src/
│       └── tests/
└── scripts/
```

### 步骤 2：配置 Agent 地图

打开项目根目录中的 `AGENTS.md` 并填写仓库级导航：
- 项目名称和目的
- 关键目录及其用途
- 验证流水线命令

如果某个子系统需要更多细节，在对应目录新增局部 `AGENTS.md`。局部地图只约束本目录及其子目录，不能放宽根规则。

### 步骤 3：自定义 CONSTRAINTS.md

定义您的架构规则：
- 层名称及每个层包含的内容
- 依赖方向规则（例如：UI 不能导入 Database）
- 文件、类和函数的命名规范

### 步骤 4：生成 harness.yaml 和 WORKFLOW.md

从 `harness.example.yaml` 生成目标项目自己的 `harness.yaml`，并填写平台、构建、测试和架构层级。保留或生成根目录 `WORKFLOW.md`，用于声明仓库级任务类型、默认 skill、validation profile 和人工确认点。目标契约不写入 `harness.yaml`；长周期或易漂移需求由 PRD review 生成 `doc/features/{feature}/outputs/goal-contract.md`：

```yaml
project:
  name: "my-project"
  language: "typescript"
harness_framework:
  schema_version: 1
  template_version: "1.5.7"
  source: "{HARNESS_TEMPLATE_REMOTE_URL}"
  source_ref: "main"
  source_commit: null
  last_upgrade: null
agent_runtime:
  enabled: true
  contract_version: "1.1.0"
evals:
  enabled: true
  index: "harness/evals/index.yaml"
platforms:
  web:
    path: "workspace/apps/web"
    build: "npm run build"
    test: "npm test"
```

`harness.yaml` 会提交到目标项目 Git，因此 `harness_framework.source` 必须是该项目实际使用的模板远程 Git URL，不要写开发者本机路径、WSL 路径、目标项目路径、`"."` 或其他项目的模板仓地址。本地模板调试使用 `harness.local.yaml` 或 `upgrade_harness.py --source ...`。

```bash
python scripts/validate_workflow.py
python scripts/lint_goal_contract.py
```

### 步骤 5：创建结果覆盖率和第一个需求

在 `BLACKBOARD.md` 中添加需求。若第一个需求是 MVP、迁移、稳定性专项等长周期或易漂移 workstream，基于 `templates/goal-contract-template.md` 生成 `doc/features/{feature}/outputs/goal-contract.md`，并在 `BLACKBOARD.md` 的“目标契约”列引用它。全新项目默认计数可用核心用户路径、验收标准、真实集成、Mock/Stub 剩余和 P0 阻塞项；迁移项目可用新系统覆盖、旧 bridge 剩余、oracle parity、owner gates 等计数：

```markdown
## Requirement: [简短标题]
[详细描述]
Status: open
```

### 步骤 6：审核您的设置

运行 harness-creator 技能来审核您的项目并完成设置。

### 步骤 7：安装本地机械门禁

```bash
python scripts/install_git_hooks.py --mode install --profile baseline
python scripts/install_git_hooks.py --mode status
```

如果是新仓库且还没有远程，先用 `bootstrap` profile：

```bash
python scripts/install_git_hooks.py --mode install --profile bootstrap
```

### 步骤 8：新开发者自检

```bash
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
```

开发者处理 `SELF-FIX`；项目负责人处理 `OWNER-ACTION`。

### 步骤 9：开始开发

使用 harness-executor 技能进行开发任务。框架将：
- 自动加载相关技能
- 强制执行您的架构约束
- 记录 runtime state、validation、result 和 handoff
- 记录执行追踪供后续审查
- 在记忆文件中累积经验

## 首个任务工作流

1. 智能体读取 AGENTS.md
2. 智能体读取 WORKFLOW.md 选择任务类型、默认 skill 和 validation profile
3. 智能体加载适当的技能（例如：修 bug 使用 harness-executor，补测试使用 code-to-test）
4. 技能指导智能体执行任务
5. 脚本验证每个步骤
6. 记录追踪，经验追加到记忆文件
7. 提交前使用 commit-review，并通过 validate_workflow 和 validate_harness_sync 检查 harness context 是否同步

## 模板仓库自我迭代

模板仓库维护时也可以运行验证、workspace-doctor 和 commit-review，但运行记录会进入 ignored 的 `harness/.local/`。详见 `harness/docs/template-runtime.md`。

## 已接入项目升级模板能力

当 harness 模板新增 skill、脚本、hooks 或 docs 时，在旧项目中运行：

```bash
python {template}/scripts/upgrade_harness.py --target {project} --mode plan --write-report
```

确认 `MANUAL` 项后再运行：

```bash
python {template}/scripts/upgrade_harness.py --target {project} --mode apply --write-report
```

升级计划会先比较目标 `harness.yaml` 的 `harness_framework.template_version` 和模板 `harness/VERSION`。如果 `harness_framework.source` 是远程 Git URL，模板会临时 clone，升级结束默认清除。
