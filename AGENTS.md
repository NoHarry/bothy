# Bothy Agent 地图

此文件只做仓库总入口。进入子目录工作时，先读本文件，再读最近的局部 `AGENTS.md`；局部规则只能细化本文件，不能放宽根规则。

## 仓库定位
- 这是 **Bothy** —— 可嵌入的 Android 设备端 AI agent 运行时，公开开源仓库（MIT）。
- 业务源码统一放在 `workspace/` 下；Gradle workspace 位于 `workspace/apps/android/`：kernel（`agent-kernel-api`、`agent-kernel-core`）、运行时 `agent-runtime`（纯 JVM）及其 Android 层 `agent-runtime-android`、虚拟文件系统 `agent-vfs`，可选模块（SAF、JS 执行、设备能力、Android Use、语音、A2A 客户端与服务端），以及演示 App `bothy-sample`。模块地图见 `workspace/apps/android/AGENTS.md`。
- 依赖方向：`bothy-sample -> agent-runtime-android -> agent-runtime -> agent-kernel-core -> agent-kernel-api`，`agent-runtime -> agent-vfs`；可选模块最多依赖 `agent-kernel-api`；没有模块依赖 sample。`bothy-playground`（虚拟座舱和小家的本机 MCP 服务）不依赖 workspace 里的任何模块。
- 本仓是 harness 目标项目；`harness.yaml` 是真实配置资产，模板来源见 `harness_framework.source`。
- **这是公开仓库**：所有提交必须通过 `scripts/lint_public_hygiene.py` 门禁（身份、密钥模式、本地词表）。

## 必读顺序
1. `python scripts/agent_runtime.py next --json`：先看本地可续接 workpad/handoff。
2. `AGENTS.md`：仓库级地图和不可放宽规则。
3. 当前任务如绑定 feature 目标契约，读 `doc/features/{feature}/outputs/goal-contract.md`。
4. 最近的局部 `AGENTS.md`。
5. `CONSTRAINTS.md`、`WORKFLOW.md`、`harness.yaml`。

## 关键目录
| 路径 | 职责 | 局部规则 |
|------|------|----------|
| `workspace/apps/android/` | Bothy Gradle workspace（runtime / vfs / sample） | `workspace/apps/android/AGENTS.md` |
| `.agent-harness/` | 多 agent registry、skill 唯一源 | `.agent-harness/AGENTS.md` |
| `.agents/` `.claude/` `.opencode/` | skill 投影，生成目录 | 不手工编辑 |
| `.githooks/` | 本地 Git hooks 入口 | `.githooks/AGENTS.md` |
| `.review/` | commit-review 项目规则 | `.review/AGENTS.md` |
| `doc/features/` | 需求流水线产物 | `doc/AGENTS.md` |
| `docs/design/` | 长期设计文档，按需创建 | 无 |
| `harness/` | 框架组件、memory、trace、eval | `harness/AGENTS.md` |
| `scripts/` | 验证、runtime、hygiene 工具 | `scripts/AGENTS.md` |
| `templates/` | 报告与交接模板 | `templates/AGENTS.md` |

## 默认工作流
| 场景 | 默认路径 |
|------|----------|
| 日常开发/修复 | `harness-executor`，使用 local runtime workpad/handoff |
| 新功能 | `prd-review -> prd-to-design -> design-to-code -> code-to-test -> commit-review` |
| 框架升级 | `harness-upgrade plan -> owner review MANUAL -> apply -> workspace-doctor -> commit-review` |
| 提交/推送 | checkpoint 走 `commit-review` checkpoint mode；push/发布走 publish mode；不得绕过 hooks |

## 不可放宽规则
- **公开仓卫生**：不得提交任何雇主/内部项目相关的名称、域名、代号、录制数据或凭据。机制：`scripts/lint_public_hygiene.py`（提交身份必须是 NoHarry noreply；通用密钥模式扫描；如存在 ignored 的 `harness/.local/denylist.txt` 则按词表扫描暂存 diff）。词表本身永不提交。
- 提交身份必须是 `NoHarry <noharry@users.noreply.github.com>`；仓库级 git config 已固定，不得用其他身份提交。
- 业务代码只进 `workspace/`；不要在仓库根新建业务 `src/`、`tests/`。
- `agent-runtime` 和 kernel 不得依赖 `bothy-sample` 或任何可选模块；sample 中的示例代码和演示用的 MCP 集成不得反向进入库模块。
- 包名：SDK 模块（`agent-*`）统一 `cc.noharry.agent.*`，SDK 里不出现 bothy；演示 App 用 `cc.noharry.bothy.sample` / `cc.noharry.bothy.playground`；公开 API 在 0.x 阶段可以演进，但破坏性变更须在 release notes 声明。
- 修改 skill 只能改 `.agent-harness/skills/src/` 和 registry，再运行 `python scripts/sync_skills.py --write`；投影目录不手工编辑。
- `CLAUDE.md` 只能是导入 `AGENTS.md` 的薄 adapter。
- durable memory 必须进入 `harness/memory/index.yaml`，含 Type、Scope、Status、Confidence、Evidence；trace/memory 内容一律使用中性表述，禁止内部上下文。
- `commit-review` 不得生成共享 `commit_log.csv` / `commit_log.xlsx`。
- 框架能力变更跟随模板版本走 `harness-upgrade`，不直接改框架文件。

## 常用命令
```bash
cd workspace/apps/android && ./gradlew testDebugUnitTest :agent-kernel-api:test :agent-kernel-core:test :agent-vfs:test :agent-runtime:test :agent-minimal:test
cd workspace/apps/android && ./gradlew :bothy-sample:assembleDebug :bothy-sample:assembleRelease
node --test workspace/apps/android/tools/agent-relay/relay.test.cjs
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
python scripts/agent_runtime.py next --json
python scripts/lint_public_hygiene.py
python scripts/validate.py --dry-run
python scripts/validate.py
```

## 文档索引
- 项目 README：`README.md`（英文，面向使用者）、`README.zh-CN.md`
- 第三方许可：`THIRD_PARTY_NOTICES.md`（语音模型、native 库、唤醒应答音频等提交在仓库里的第三方文件）
- 框架说明：`harness/docs/`
- 执行契约：`WORKFLOW.md`；机械约束：`CONSTRAINTS.md`

## 坑点
- 不要把 build 产物、`local.properties` 或 `harness/.local/` 提交进仓库。
- `bothy-sample` 的 provider 配置 asset 打进 APK，不得含真实 key；真实 key 走 ignored 的 `workspace/apps/android/local.properties`。
- ReleaseHardeningTest 会按相对路径读取 `../bothy-sample/`；移动或改名模块时同步更新。
