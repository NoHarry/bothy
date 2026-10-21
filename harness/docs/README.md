# Harness 文档

## 什么是 Harness 工程？

一种将代码仓库作为 AI 智能体操作系统的方法。所有上下文、约束、技能和记忆都存储在仓库中，而不是在聊天历史中。

## 目录结构

```
harness/
├── docs/          # harness 框架文档和指南
│   ├── README.md  # 本文件
│   └── getting-started.md
│   └── workspace-doctor.md
│   └── git-hooks.md
│   └── roles.md
│   └── first-week-handoff.md
│   └── harness-upgrade.md
│   └── template-runtime.md
│   └── harness-dream.md
│   └── memory-governance.md
│   └── hook-bus.md
│   └── skills-sync.md
│   └── agent-tool-adapters.md
│   └── agent-runtime-contract.md
│   └── runtime-requirements.md
│   └── eval-governance.md
│   └── workflow-contract.md
├── doc-local/     # doc 为 submodule 时的本地草稿区
├── dream/         # 目标项目 dream report 路径说明；reports 运行时创建
├── memory/        # 带 index / 证据 / 生命周期的工程记忆
│   ├── index.yaml
│   ├── domain-api-gotchas.md
│   ├── process-improvements.md
│   ├── tool-environment-lessons.md
│   ├── failure-patterns.md
│   └── decisions.md
├── evals/         # 经 review 的仓库级 eval 元数据骨架
└── trace/         # 执行追踪（每个任务一个）
    └── README.md
```

## 三大支柱

1. **约束**（CONSTRAINTS.md）—— 由脚本强制执行的机械规则
2. **技能**（.agent-harness/skills/src/）—— 通用任务的可复用工作流源，投影到各 agent 工具目录
3. **记忆**（harness/memory/）—— 从过去执行中累积的经验教训

## 工作原理

1. 智能体读取根 AGENTS.md 和最近的局部 AGENTS.md 以了解项目
2. 智能体加载任务相关的技能
3. 技能逐步指导执行
4. 脚本自动强制执行约束
5. 经验教训经证据和作用域确认后写入 memory/ 并同步 index.yaml
6. 执行追踪记录在 trace/
7. 新人或回接手开发者使用 workspace-doctor 诊断本地环境和下一步
8. 周期性使用 harness-dream 生成只读复盘报告
9. 较完整的 agent 任务使用 agent-runtime 记录 state、validation、eval、result 和 handoff
10. `WORKFLOW.md` 声明仓库级执行契约，并由 `scripts/validate_workflow.py` 校验

## 与 doc/、docs/ 的边界

- `doc/features/`：需求、评审、functional-spec、goal-contract 等 feature 流水线产物。
- `docs/design/`：目标业务项目的长期设计契约，包含 summary/detail design。
- `harness/docs/`：本 harness 模板和框架说明。
- `docs/`：目标业务项目初始化后按需创建的工程文档。

## 验证流水线

运行 `scripts/validate.py`，执行以下步骤：

1. **build** —— 编译或转译项目
2. **lint-arch** —— 检查架构规则违规
3. **test** —— 运行测试套件
4. **verify** —— 最终验证通过

目标项目应先从 `harness.example.yaml` 生成真实 `harness.yaml`，再运行验证流水线。

## 本地机械层

- `scripts/install_git_hooks.py`：安装仓库级 `.githooks`，不修改全局 Git 配置。
- `scripts/validate_harness_sync.py`：检查业务/工作流变更是否同步 harness context。
- `scripts/validate_workflow.py`：检查 `WORKFLOW.md` 的 front matter、skill 引用、validation profile、workspace hooks 和危险字段。
- `scripts/harness_runtime.py`：区分 template/target，选择运行记录输出路径。
- `harness/docs/runtime-requirements.md`：声明 Python/PyYAML 运行时基线和 fallback YAML 边界。
- `scripts/lint_agent_entrypoints.py`：保证 `CLAUDE.md` 只作为 `AGENTS.md` 的薄 adapter，并在模板仓库检查框架局部 AGENTS 地图。
- `scripts/lint_template_clean.py`：模板仓库洁净门禁，防止运行记录进入模板资产。
- `scripts/lint_memory.py`：检查 `harness/memory/index.yaml`、entry schema 和 evidence 是否一致。
- `scripts/workspace_doctor.py`：给新开发者和长期未维护后回接手的人输出 SELF-FIX、OWNER-ACTION 和 READ-FIRST。
- `scripts/upgrade_harness.py`：给已接入项目生成/应用 harness 模板升级计划。
- `scripts/agent_runtime.py`：管理本地/worker agent 运行契约和 handoff。
- `scripts/dream_runner.py`：读取 trace/memory/hardening 信号并生成只读 dream report。
- `scripts/hook_bus.py`：统一 Claude Code / Codex / OpenCode 的 hook event 输入。
- `scripts/sync_skills.py`：检查多 agent skill projection 是否漂移。

当前模板先落地本地机械层；CI 门禁由目标项目后续根据团队节奏单独接入。
