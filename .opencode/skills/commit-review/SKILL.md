---
name: commit-review
description: 代码提交前进行AI评审的Skill。支持 checkpoint/publish 两种模式：checkpoint 用于长运行本地进度保存，publish 用于推送/MR 前完整评审。自动检查git状态、验证提交信息格式、使用AI分析代码改动质量，publish 评分>=80分才允许提交。当用户提到提交代码、checkpoint、commit、或需要代码评审时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Commit Review Skill

对代码提交进行AI评审，确保代码质量和提交信息规范。

## 何时使用

当用户有以下意图时激活此Skill：
- 提到 "提交代码", "commit", "提交"
- 提到 "checkpoint", "WIP", "保存进度"
- 输入 `/commit-review`
- 需要进行代码评审
- 准备push代码到远程

## 工作流程

### 0. 选择模式

先判断用户意图，必须二选一：

| 模式 | 适用场景 | 行为 |
|------|----------|------|
| `checkpoint` | 长运行任务中保存一批进度、WIP/checkpoint、本地回滚点 | 本地提交；跳过 sync-latest、完整 AI 评分、push/MR 和目标分支 fan-out；仍使用 `commit-review` 入口、普通 git hooks 和轻量门禁 |
| `publish` | 用户说提交代码、推送、准备 MR、交付、最终提交 | 使用本 skill 原完整流程：同步最新代码、完整 AI review、评分门槛、commit-batch、push/MR |

默认规则：
- 用户明确说 checkpoint/WIP/保存进度 → `checkpoint`。
- 用户明确说 push/MR/发布/交付/提交代码 → `publish`。
- 用户只说 commit 但上下文是 goal-contract 长运行批次 → 先解释将按 checkpoint 处理；如用户要求推送再切 publish。
- checkpoint 不是最终评审结论；publish 时必须评审从目标基线到当前 HEAD 的累计 diff。

### 1. Publish 模式：同步最新代码（必须执行，禁止跳过）

**⚠️ 这是整个流程的前置条件，必须在任何其他步骤之前执行。禁止以任何理由跳过此步骤（包括"刚拉过代码"、"没有远程更新"等）。**

Checkpoint 模式跳过本步骤；checkpoint 不 rebase、不 push、不处理目标分支。

**执行方式：直接运行 sync-latest 脚本，等待脚本执行完毕后再进入下一步。**

```bash
BATCH_NON_INTERACTIVE=1 bash .agents/skills/sync-latest/scripts/sync-latest.sh
```

此脚本会自动完成：
- 主模块：fetch → rebase 到 origin/$MAIN_SYNC_BRANCH（自动探测，有未提交更改时自动 stash/pop）
- 每个子模块：同上流程
- 如有冲突：提示用户解决，解决后自动完成 git add / rebase --continue

**验证：脚本输出 "所有模块同步完成" 后才可进入步骤2。如果脚本报错或中止，必须先解决问题再重新执行。**

### 2. 检查Git状态

检查当前目录和子模块的未提交更改：

```bash
bash .agents/skills/commit-review/scripts/commit-review.sh status
```

### 2.1 检查本地机械门禁状态

必须执行：

```bash
python scripts/install_git_hooks.py --mode status
python scripts/validate_workflow.py
python scripts/validate_harness_sync.py
python scripts/agent_runtime.py status --json
```

如果 hooks 未安装：
- 本次评审继续，但必须在评分中扣除本地门禁缺失风险。
- 提示运行 `python scripts/install_git_hooks.py --mode install --profile baseline`。
- 如果已有非 `.githooks` 的 hooksPath，不要强制覆盖，记录为 OWNER-ACTION。

提交日志规则：
- `commit-review` 不得创建、写入或导出 `commit_log.csv` / `commit_log.xlsx`，也不得把这些文件作为提交证据。
- 如仓库中已有历史遗留的 `commit_log.csv` / `commit_log.xlsx`，本次提交必须保持不暂存；共享 CSV 在多人提交时容易产生无意义冲突。
- 评审证据保留在本次评审输出、agent runtime 的 `result.md` / trace、MR 描述或 review notes 中。

如果 `validate_workflow.py` 失败：
- 评分不得达到 80 分。
- 如果变更涉及 `WORKFLOW.md`、validation profile、workspace hook 或 human gate，必须要求 owner review。

如果 `validate_harness_sync.py` 失败：
- 评分不得达到 80 分。
- 要求补齐 README / AGENTS / CONSTRAINTS / WORKFLOW.md / harness.yaml / memory / trace / `.review/rules` 等同步产物后再提交。

如果存在当前 agent runtime run：
- 读取 `python scripts/agent_runtime.py current --json`、`python scripts/agent_runtime.py resume --json` 和 `python scripts/agent_runtime.py check`。
- 对开发/修 bug 类任务，缺少 `result.md`、`handoff.md` 或验证证据属于评审风险；除非本次是纯文档/配置同步且已有明确验证说明，否则评分不得达到 90 分。
- 如果 runtime workpad 的 Completion Bar 有未完成项，只输出 advisory reminder，不阻断提交、不降分、不自动修改 runtime 状态。提醒中列出 run id、pending completion、pending validation，并说明 checkpoint/WIP 提交可以继续。
- `harness/.local/agent-runtime/**` 不得被提交；runtime memory/eval candidate 只能作为证据，不能未经 review 直接进入 durable memory 或 repo eval。

### 2.2 Checkpoint 模式：轻量本地进度保存

Checkpoint 模式只用于本地保存进度，必须满足：
- commit message 固定格式：`chore(checkpoint): {short description}`。
- 不允许包含 `&target-branch&` 标记。
- 不推送、不创建临时目标分支、不生成 MR。
- 不生成或暂存 `commit_log.csv` / `commit_log.xlsx` / `harness/.local/**`。
- 仍使用普通 `git commit`，本地 hooks 继续生效；不得使用 `--no-verify`。
- 后续 publish 模式必须对累计 diff 做完整 review。

执行：

```bash
bash .agents/skills/commit-review/scripts/commit-checkpoint.sh "chore(checkpoint): {batch summary}"
```

Checkpoint 结束后输出：
- checkpoint commit hash；
- 本批移动的结果计数（如绑定 goal-contract）；
- 已运行的轻量门禁和 hooks 结果；
- 下一批起点。

Checkpoint 模式到此结束，不进入步骤 2.5 之后的 publish 评审流程。

### 2.5 Publish 模式：评审复杂度分析

对每个有改动的模块，分析 diff 规模，**在评审前**决定评审深度。详细路由规则见 [增强版评审工作流](references/AGENT_WORKFLOW.md) 的"复杂度路由规则"章节。

| 评审级别 | 触发条件 | 行为 |
|---------|---------|------|
| 轻量评审 | 仅注释/文档改动 ≤ 10 行 | 只检查注释准确性，快速通过 |
| 标准评审 | ≤ 200 行 & ≤ 5 文件 | 完整 5 维度评审 |
| 深度评审 | > 200 行 或 > 5 文件 | 完整评审 + LSP 上下文收集 |

### 3. Publish 模式：收集提交信息

交互式询问提交信息，**只有 type 和 description 必填**，格式如下：

```
type: description
```

或带可选字段：
```
type(scope): description#TB-ID#&target-branch1&target-branch2&
```

**字段说明**：
- `type` - **必填**，提交类型
- `(scope)` - **可选**，影响范围（如auth、api、ui等）
- `description` - **必填**，描述信息
- `#TB-ID#` - **可选**，任务单号（如#TB-1234#）
- `&target-branch&` - **可选**，临时分支目标。仅在用户明确提供时才添加。**严禁自行添加！** 用户不写就不加。格式规则：每个分支名用 `&` 包裹，多个分支连写。例如 `&release_1_0&release_1_0_hotfix&` 表示两个目标分支 `release_1_0` 和 `release_1_0_hotfix`，**不是一个叫 `release_1_0&release_1_0_hotfix` 的分支**。解析方式：用 `&` 分割，去掉空串，每个非空串就是一个分支名。

**type类型**：
- `feat` - 新功能开发
- `fix` - 修复错误
- `docs` - 更新文档
- `style` - 代码风格更新
- `refactor` - 代码重构
- `perf` - 性能优化
- `test` - 添加测试
- `chore` - 构建/配置

**示例**：

极简格式（仅必填）：
```
feat: 添加登录验证功能
```

带scope：
```
feat(auth): 添加登录验证功能
```

带TB-ID：
```
feat: 添加登录验证功能#TB-1234#
```

完整格式（所有可选字段）：
```
feat(auth): 添加登录验证功能#TB-1234#&develop&master&
```

多目标分支示例（常用）：
```
feat: 增加注释&release_1_0&release_1_0_hotfix&
```
上面表示两个目标分支：`release_1_0` 和 `release_1_0_hotfix`。提交后会为每个目标分支创建临时分支并 cherry-pick。

### 4. Publish 模式：格式验证

使用正则验证提交信息格式：
```regex
^(feat|fix|docs|style|refactor|perf|test|chore)(\([a-zA-Z0-9_-]+\))?: .+(#TB-[0-9]+#)?(&[a-zA-Z0-9_/-]+&)*$
```

如果验证失败：
- 评分0分
- 显示正确格式示例
- 禁止提交

### 5. Publish 模式：逐一评审所有模块（先全部评审，再统一提交）

**重要：先评审所有模块，全部通过后再统一提交！**

#### 5.1 第一阶段：收集所有评审结果

```
═══════════════════════════════════════════════════════
           阶段1: 评审所有模块（不提交）
═══════════════════════════════════════════════════════
```

对于每个有改动的模块（子模块+主项目）：
1. 显示模块信息
2. 获取代码Diff
2.5. **上下文收集**（深度评审模式时执行）：使用 LSP 工具收集被修改方法的接口定义和调用者信息，注入评审上下文。详见 [增强版评审工作流](references/AGENT_WORKFLOW.md) 的"上下文收集指令"章节。轻量/标准模式跳过此步骤，失败不阻断流程。
3. AI代码评审
   按 [增强版评审工作流](references/AGENT_WORKFLOW.md) 执行——使用 JSON 中间格式输出评审结果、按确定性评分矩阵计算分数、检查项目 `.review/rules/*.md`（参考 [规则框架说明](rules/README.md)）、完成后执行质量自检清单。
4. **记录评审结果**（评分和意见）
5. **暂不提交！**

**评审标准**：
- ≥80分：通过 ✓
- <80分：不通过 ✗

**评审重点（AI必须逐项检查并输出结论）**：

| 检查项 | 说明 | 示例问题 |
|--------|------|----------|
| 缺陷 | 空指针、数组越界、资源泄漏、并发问题、逻辑错误 | `getString()` 返回 null 未判空 |
| 安全 | 硬编码密钥、SQL注入、XSS、权限校验缺失 | API密钥写在代码里 |
| 兼容性 | API版本兼容、多设备适配、向后兼容 | 使用了 minSdk 26 的 API 但项目 minSdk 是 21 |
| 性能 | 主线程耗时操作、内存泄漏、冗余计算 | 在 UI 线程做网络请求 |
| 改进建议 | 可选的代码优化建议（不影响评分） | 建议抽取常量避免魔法数字 |

**评审意见格式要求**：
- 每条意见必须指出具体文件和行号（如有）
- 必须说明问题是什么、为什么是问题、建议如何修复
- 如果没有发现问题，写"未发现缺陷"即可，**不要写空洞的夸奖**（如"代码结构清晰"、"逻辑合理"）
- 纯注释/文档类改动可以简短评审，但仍需检查注释是否准确

**交互示例**：

```
═══════════════════════════════════════════════════════
           评审模块: lib_resource
═══════════════════════════════════════════════════════

[AI评审中...]

评分: 85/100 ✓ 通过

评审意见：
• [缺陷] ResourceLoader.java:42 - loadConfig() 返回值可能为 null，调用方 L58 未判空，建议添加空值检查
• [改进] ResourceLoader.java:30 - TIMEOUT_MS=5000 建议抽为常量

═══════════════════════════════════════════════════════
           评审模块: module_action
═══════════════════════════════════════════════════════

[AI评审中...]

评分: 65/100 ✗ 不通过

评审意见：
• [缺陷] Launch.java:85 - handleIntent() 中 intent.getStringExtra("key") 未判空，直接调用 .equals() 会 NPE
• [安全] AppUtils.java:120 - 硬编码了包名字符串，如果包名变更会导致功能失效，建议从 PackageManager 动态获取
• [性能] Launch.java:92 - 在主线程调用 queryPackageInfo()，可能导致 ANR
```

#### 5.2 第二阶段：统一判断是否提交

```
═══════════════════════════════════════════════════════
           阶段2: 评审结果汇总
═══════════════════════════════════════════════════════

评审统计:
  通过: 2 个模块
  不通过: 1 个模块

详细结果:
  ✓ lib_resource: 85/100
  ✗ module_action: 65/100 (存在空指针风险)
  ✓ module_speech: 90/100
```

**判断逻辑**：

```
如果所有模块都通过（fail_count == 0）:
    └── 询问: "所有模块评审通过，是否统一提交? (y/n):"
        └── 用户输入 'y': 进入阶段3，统一提交所有模块
        └── 用户输入 'n': 取消提交

如果有任何模块不通过（fail_count > 0）:
    └── 显示: "以下模块评审不通过，请修改后重新提交:"
    └── 列出所有不通过的模块及其评审意见
    └── 退出，不提交任何代码
```

#### 5.3 第三阶段：统一提交（仅当全部通过且用户确认时）

```
═══════════════════════════════════════════════════════
           阶段3: 统一提交
═══════════════════════════════════════════════════════
```

**重要：必须使用 `commit-batch.sh` 脚本执行提交！该脚本会自动处理 rebase 同步 + 提交 + force-push，确保源分支不会 behind 目标分支。严禁手动执行 git add/commit/push 替代此脚本！**

此要求只适用于 publish 模式。checkpoint 模式必须使用 `commit-checkpoint.sh`，不得调用 `commit-batch.sh`。

**执行步骤**：

**第1步：保留评审证据，但不要写共享 CSV**

提交前必须把步骤5的评审结论整理到本次交互输出、agent runtime `result.md` / trace、MR 描述或 review notes 中。不得调用 `logger.sh` 生成 `commit_log.csv`，也不得把 `commit_log.csv` / `commit_log.xlsx` 纳入提交。

评审意见摘要要求：
- 必须是步骤5评审阶段输出的具体意见压缩版，直接从评审结果中提取，不得重新编造或用空洞总结替代。
- 标签必须是以下之一：`[缺陷]` `[安全]` `[兼容]` `[性能]` `[改进]`。
- 每条格式：`[标签]文件名:行号-具体问题描述`（行号未知时可省略）。
- 多条用分号 `;` 分隔；多模块用 `|` 分隔，格式：`模块名:{意见};{意见}|模块名:{意见}`。
- 有问题时逐条列出，每条必须有文件名和问题描述。
- 确实无问题时写 `未发现缺陷`，仅当评审中每个检查项都确认无问题时才可使用。
- 纯注释/文档改动写 `[改进]具体文件-注释内容是否准确的结论`。

禁止写的内容：
- `代码质量良好` / `代码结构合理` / `逻辑清晰` / `全部通过`
- `主模块88分，module_eventtrack92分，均通过`（这是评分不是意见）
- 任何不包含具体文件名或具体问题描述的笼统评价

示例：
```text
# 单模块有问题
[缺陷]Launch.java:85-intent未判空可能NPE;[性能]Launch.java:92-主线程调用queryPackageInfo可能ANR;[改进]AppUtils.java:30-建议抽取常量

# 多模块
lib_resource:[缺陷]ResourceLoader.java:42-loadConfig返回null未判空|module_action:[安全]AppUtils.java:120-硬编码包名

# 纯注释改动确认无问题
未发现缺陷

# 注释改动但有问题
[改进]Config.java:15-注释描述与实际逻辑不符,timeout单位是秒非毫秒
```

**第2步：调用 commit-batch.sh 执行批量提交**

第二个参数是 rebase 同步的主分支，必须从 `config.sh` 中读取 `MAIN_SYNC_BRANCH` 的值（由脚本自动探测，无需硬编码）：

```bash
# 先读取配置中的同步分支
source .agents/skills/commit-review/scripts/config.sh
# 设置非交互模式（AI执行时必须设置，遇到冲突会自动中止而不是死循环等待输入）
# 第二个参数传 $MAIN_SYNC_BRANCH，不要填 &branch& 里的分支名
BATCH_NON_INTERACTIVE=1 bash .agents/skills/commit-review/scripts/commit-batch.sh "{完整commit信息}" "$MAIN_SYNC_BRANCH"
```

**⚠️ 关于第一个参数（完整commit信息）的关键要求：**
- **必须传用户提供的原始完整提交信息，包括 `&branch&` 部分！**
- 例如用户提交信息是 `feat: 增加注释&release_1_0&release_1_0_hotfix&`，第一个参数就必须是 `"feat: 增加注释&release_1_0&release_1_0_hotfix&"`
- **严禁去掉 `&branch&` 部分！** 脚本依赖提交信息中的 `&branch&` 标记来自动创建临时分支（子模块和主模块都会处理）
- 如果去掉了 `&branch&` 部分，脚本不会创建任何临时分支，导致目标分支同步缺失

**⚠️ 第二个参数是 rebase 同步的主分支（来自 config.sh 的 MAIN_SYNC_BRANCH），不是提交信息中 `&branch&` 里的分支名！`&release_1_0&` 等是提交后创建临时分支用的，由脚本内部自动解析处理。**

`commit-batch.sh` 会自动完成以下所有操作：
1. 收集有本地变更的子模块
2. 逐个提交子模块（每个子模块：fetch → rebase到目标分支 → add → commit → rebase → force-push）
3. 处理子模块临时分支（如果提交信息包含 `&branch&`）
4. 更新主模块中的子模块引用
5. 提交主模块（fetch → rebase到目标分支 → add -A 但排除 `commit_log.csv` / `commit_log.xlsx` → commit → rebase → force-push）
6. 处理主模块临时分支（如果提交信息包含 `&branch&`）
7. 显示MR链接

**关键机制（由脚本自动处理）**：
- 每个模块提交前会 `git rebase --rebase-merges origin/{目标分支}` 同步最新代码
- 推送使用 `git push --force-with-lease` 确保 rebase 后能正确推送
- 这样源分支始终基于目标分支最新代码，MR时不会出现 "behind" 的情况

**示例**：
```bash
# 先读取配置
source .agents/skills/commit-review/scripts/config.sh

# 执行批量提交（BATCH_NON_INTERACTIVE=1 标识AI非交互模式，第二个参数用 $MAIN_SYNC_BRANCH）
BATCH_NON_INTERACTIVE=1 bash .agents/skills/commit-review/scripts/commit-batch.sh "feat(auth): 添加登录验证" "$MAIN_SYNC_BRANCH"
```

### 6. 冲突处理（commit-batch.sh 失败时）

当 `commit-batch.sh` 因冲突退出时，冲突状态会保留在原地（不会自动 abort）。AI 必须按以下流程处理：

**6.1 分析脚本输出，提取冲突信息**

从脚本输出中提取：
- 冲突发生在哪个阶段（sync rebase / 子模块提交 / 临时分支 cherry-pick / 主模块提交）
- 冲突的模块名、模块路径、冲突文件列表
- 当前所在的分支（临时分支名或开发分支名）
- 已成功完成的步骤（不需要重复执行）

**6.2 向用户展示冲突信息并提供选择**

使用 AskUserQuestion 向用户提供选项，**用户只需要编辑冲突文件并保存，所有 git 命令由 AI 执行**：

```
冲突信息：
  模块: {模块名}
  阶段: {cherry-pick / rebase}
  冲突文件:
    - {文件路径1}
    - {文件路径2}

请在编辑器中解决冲突文件后选择操作：
  [已解决] - 我已编辑并保存了冲突文件，请继续
  [跳过]   - 跳过这个目标分支的 cherry-pick
  [中止]   - 放弃整个临时分支处理
```

然后**等待用户回复**，不要自行操作。

**6.3 根据用户选择执行对应的 git 命令**

**用户选择"已解决"**：
```bash
# 进入冲突模块目录（如果是子模块）
cd {模块路径}

# AI 执行：暂存已解决的文件并继续
git add .
git cherry-pick --continue   # 或 git rebase --continue（取决于冲突阶段）

# AI 执行：推送分支
git push --set-upstream origin {分支名}

# AI 执行：切回开发分支，清理临时分支
git checkout {开发分支}
git branch -D {临时分支名}

# 如果还有其他目标分支未处理，继续创建临时分支并 cherry-pick
```

**用户选择"跳过"**：
```bash
cd {模块路径}
git cherry-pick --skip   # 或 git cherry-pick --abort
git checkout {开发分支}
git branch -D {临时分支名}
# 继续处理下一个目标分支
```

**用户选择"中止"**：
```bash
cd {模块路径}
git cherry-pick --abort
git checkout {开发分支}
git branch -D {临时分支名}
# 停止处理，不再创建其他临时分支
```

**6.4 继续执行剩余步骤**

冲突解决后，AI 需要继续完成 commit-batch.sh 未完成的步骤：
- 如果还有其他目标分支的临时分支未创建，继续处理
- 如果子模块临时分支全部完成，继续处理主模块提交和主模块临时分支
- 最后显示摘要和 MR 链接

### 7. 显示摘要

`commit-batch.sh` 执行完成后会自动显示提交统计和MR链接。AI只需展示脚本输出即可。

```
═══════════════════════════════════════════════════════
           处理完成
═══════════════════════════════════════════════════════

提交统计:
  成功: 2 个模块
  失败: 0 个模块

═══════════════════════════════════════════════════════
           Merge Request 链接
═══════════════════════════════════════════════════════

【主模块】
  分支: develop_lhy → develop
  链接: https://gitlab.com/group/project/-/merge_requests/new?...

═══════════════════════════════════════════════════════

评审证据已保留在本次输出 / agent runtime / MR 描述中；未生成 commit_log.csv。
```

**MR链接说明**：
- 自动从git remote URL生成GitLab MR链接
- 支持SSH和HTTPS格式的remote URL
- 源分支：本地开发分支（如 develop_lhy）
- 目标分支：配置的同步分支（如 develop）

## 评审证据格式

`commit-review` 不再维护共享 CSV 日志。多人并行提交时，仓库级 `commit_log.csv` 会频繁产生无业务意义的冲突，因此该 skill 必须把评审证据保留在提交上下文中，而不是写入文件。

评审证据至少包含：
- 提交信息和目标分支
- 每个模块的评分和通过/不通过结论
- 具体评审意见摘要
- 已执行的机械门禁命令和结果
- 冲突处理或 owner action（如有）

Checkpoint 证据可以更轻，只需包含：
- checkpoint commit hash；
- 批次范围和结果计数变化；
- 轻量门禁 / hooks 结果；
- publish 前仍需完整 review 的提醒。

## 分支配置

### 自动探测（默认行为）

`config.sh` 会自动探测远程分支，优先级：`develop` > `main` > `master`。如果远程有 `develop` 分支则使用它，否则依次回退。无需任何手动配置。

### 手动覆盖（可选）

在项目根目录创建 `.review/config.sh` 文件可覆盖探测结果：

```bash
# .review/config.sh（项目根目录，按需创建）
MAIN_SYNC_BRANCH="your-branch"       # 覆盖主模块同步分支
SUBMODULE_SYNC_BRANCH="your-branch"  # 覆盖子模块同步分支
```

### 无子模块项目

`commit-batch.sh` 已内置子模块检测（`_has_submodules()`）。对于无子模块的项目，步骤1（收集子模块）、步骤2（提交子模块）、步骤3（子模块临时分支）、步骤4（更新子模块引用）会自动跳过，直接进入主模块提交。无需任何额外配置。

## 相关脚本

- `../sync-latest/scripts/sync-latest.sh` - 拉取最新代码（独立Skill）
- `scripts/commit-review.sh` - Git操作执行
- `scripts/commit-checkpoint.sh` - 长运行本地 checkpoint 提交（不 push、不 fan-out）
- `scripts/branch-handler.sh` - 临时分支处理
- `scripts/logger.sh` - 旧版兼容 no-op；不会生成 `commit_log.csv`
- `scripts/utils.sh` - 工具函数
- `scripts/config.sh` - 配置


## 参考文档

- [Agent工作流详细说明](references/AGENT_WORKFLOW.md)
- [测试场景](references/TEST_SCENARIOS.md)
