---
name: memory-distill
description: 压缩和整合 harness memory 文件，保持上下文精简而不丢失知识。归档原始条目、去重、提炼原则并产出压缩摘要。当 memory 文件超过 50 条或 500 行时触发。当用户提到"压缩记忆"、"清理 memory"、"distill memory"、"知识整合"时激活此Skill。
license: MIT
metadata:
  author: noharry
  team: infra
  version: "1.0.0"
---

# Skill: Memory Distill

> 压缩并整合 harness memory 文件，保持上下文精简而不丢失知识。
> 归档原始条目、去重、提炼原则并产出蒸馏摘要。

## 何时使用

- 任意 memory 文件（`harness/memory/*.md`）超过 **50 条目** 或 **500 行**
- Agent 在 `harness-executor` Step 1（Load Context）期间注意到上下文变得嘈杂
- 用户明确请求："distill memory"、"compress knowledge"、"clean up memory"
- 周期性：每完成 **10 个任务** 后检查一次（检查自上次 distill 以来的 trace 数量）
- runtime memory candidate 需要经过确认后提升为 durable memory

## 设计原则

1. **归档而非删除** — 原始条目移入 `harness/memory/archive/`，永不销毁
2. **有损压缩但有审计追踪** — 蒸馏后的文件链接回归档的原始文件
3. **LLM 驱动合并** — 通过语义判断进行去重和原则提炼，而非盲目字符串匹配
4. **保留因果性** — 同一主题但不同时间/原因的条目应保持独立，除非确实冗余
5. **先计划后修改** — 必须先输出 merge plan，获得确认后才允许改 memory
6. **索引同步** — 修改 durable memory 时必须同步 `harness/memory/index.yaml`
7. **候选不直升** — dream / runner 产出的 candidate memory 不能未经确认直接变成 `active`

## 前置条件

- `harness/memory/` 存在且至少包含一个含有条目的 memory 文件
- Agent 对所有 memory 文件有读权限
- 若存在 `harness/memory/index.yaml`，蒸馏前必须运行一次 `python scripts/lint_memory.py`
- 若要处理 runtime candidate，只能读取 `harness/.local/agent-runtime/runs/*/memory-candidates.yaml` 作为证据输入，不得直接复制为 active memory。

## 输入参数

| 输入 | 必填 | 默认值 |
|------|------|--------|
| 目标文件 | 否 | `harness/memory/*.md` 下所有文件（不含 README.md） |
| 压缩级别 | 否 | `standard` — 见下方级别说明 |

### 压缩级别

| 级别 | 行为 |
|------|------|
| `light` | 仅去重 — 合并近似重复条目，保留其余一切 |
| `standard` | 去重 + 按主题聚类 + 从 3 条及以上相似条目中提炼原则 |
| `deep` | 标准级别 + 将 30 天前的所有条目总结为话题级摘要 |

## 工作流程

### 步骤 0: 度量当前状态

对每个 memory 文件（`domain-api-gotchas.md`、`process-improvements.md`、`tool-environment-lessons.md`、`failure-patterns.md`、`decisions.md`）：

1. 统计总条目数（每个 `### [date]` 标题 = 1 条）
2. 统计总行数
3. 记录文件大小

输出状态表格：

```
| File | Entries | Lines | Last Distill |
|------|---------|-------|--------------|
| domain-api-gotchas.md | 47 | 380 | 2026-03-01 |
| process-improvements.md | 12 | 96 | never |
| tool-environment-lessons.md | 8 | 64 | never |
```

若所有文件均少于 20 条 → 报告 "Memory is lean, no distill needed" 并 **停止**。若只是要合并 dream candidate，仍需输出 candidate merge plan，不得直接写入 durable memory。

### 步骤 1: 归档当前状态

在任何修改之前，创建带时间戳的归档：

```
harness/memory/archive/{YYYY-MM-DD}-pre-distill/
  ├── domain-api-gotchas.md        ← 精确副本
  ├── process-improvements.md      ← 精确副本
  ├── tool-environment-lessons.md  ← 精确副本
  ├── failure-patterns.md          ← 精确副本
  ├── decisions.md                 ← 精确副本
  └── index.yaml                   ← 精确副本
```

这是**安全网**。若蒸馏过程丢失重要内容，原始文件可恢复。

### 步骤 2: 识别重复聚类

对每个 memory 文件和 `harness/memory/index.yaml`，读取所有条目并识别聚类：

如果输入包含 dream report 或 runtime memory candidate，先把每条候选项分类为：
- APPROVE：证据充分，可进入 merge plan
- REVIEW_REQUIRED：需要负责人确认
- REJECT：一次性调试信息或无证据

只有 APPROVE 项可以进入后续 merge plan，且初始状态一般应为 `candidate` 或 `active`，不能默认 `promoted_to_rule`。

**2a. 精确/近似重复**（同一问题、同一解决方案、不同日期）：
- 判定标准：根本原因相同且解决方案相同，只是遇到的时间不同
- 操作：**合并**为单一条目，注明所有遇到过的日期
- 示例：三条关于"API 在最后一页返回空数组"的条目 → 一条条目注明 "Encountered: 2026-01-15, 2026-02-20, 2026-03-10"

**2b. 主题聚类**（相关问题领域，具体表现不同）：
- 判定标准：同一领域/API/工具，但表现形式不同
- 操作：**保留各条目**，但在主题标题下归组
- 示例：五条关于 Redis 边缘情况的条目 → 在 `## Redis Gotchas` 下归组

**2c. 矛盾**（新条目取代旧条目）：
- 判定标准：后续条目明确解决或与先前条目矛盾
- 操作：**保留较新的**，将较旧的归档并注明 "Superseded by entry [date]"
- 示例："Workaround: use legacy flag" → 后来 "Fixed in v3.2, remove legacy flag"

在执行前输出合并计划：

```
## Merge Plan

### domain-api-gotchas.md
- MERGE: entries [2026-01-15], [2026-02-20] → same pagination issue (keep [2026-02-20] as canonical, note both dates)
- SUPERSEDE: [2026-01-10] by [2026-03-05] → auth flow was fixed in SDK v2
- CLUSTER: [2026-02-01], [2026-02-15], [2026-03-01] → group under "## Payment API Gotchas"
- KEEP: [2026-01-20] → unique, no similar entries

### process-improvements.md
- (no changes needed — only 12 entries)

### index.yaml
- ADD: mem-2026-05-12-010 for approved candidate "..."
- UPDATE: mem-2026-05-12-002 status active -> promoted_to_rule after hook hardening
- SUPERSEDE: mem-2026-04-01-003 by mem-2026-05-12-010
```

**停止并向用户/Agent 展示合并计划，等待确认后再继续。**

合并计划必须列出：
- 每个新增、修改、归档、supersede 的 memory id
- `type`、`scope`、`status`、`confidence`
- 证据路径
- 目标文件
- 是否需要 owner action 或 hardening debt

### 步骤 3: 提炼原则（standard + deep 级别适用）

对于包含 **3 条及以上条目** 的主题聚类，提炼出通用原则：

**输入**（3 条具体条目）：
```
### [2026-01-15] Payment API rejects decimal amounts
### [2026-02-01] Payment API requires currency as ISO 4217
### [2026-03-10] Payment API timeout is 5s not 30s
```

**输出**（1 条原则 + 3 条归档的具体条目）：
```
### [PRINCIPLE] Payment API Integration Rules
- **Distilled from**: 3 entries (2026-01-15, 2026-02-01, 2026-03-10)
- **Archive**: harness/memory/archive/2026-04-20-pre-distill/domain-api-gotchas.md
- **Rules**:
  1. Always use integer amounts (cents), never decimals
  2. Currency codes must be ISO 4217 (e.g., "CNY" not "RMB")
  3. Set timeout to 5s — default 30s will cause cascading failures
- **When in doubt**: Check the archived originals for full context
```

原则**比单独条目更有用**，因为它们给 Agent 提供的是可遵循的规则，而非需要解读的故事。

### 步骤 4: 时间聚合摘要（deep 级别适用）

对于 30 天前且未被合并或原则化的条目：

1. 按月份分组
2. 将每月总结为一段紧凑文字
3. 保留摘要，归档单独条目

```
### [SUMMARY] January 2026 Lessons
- **Covers**: 8 entries from 2026-01-01 to 2026-01-31
- **Archive**: harness/memory/archive/2026-04-20-pre-distill/
- **Key takeaways**:
  - API pagination: check array length, not HTTP status
  - Auth tokens: refresh 5min before expiry, not on 401
  - File uploads: stream, never buffer entire file in memory
```

### 步骤 5: 重建 Memory 文件

用以下结构重写每个 memory 文件：

```markdown
# {Category Title}

{Original description and format section — preserved exactly}

---

## Principles (distilled from multiple entries)

### [PRINCIPLE] {Topic}
...

## Monthly Summaries (deep distill only)

### [SUMMARY] {Month Year}
...

## Active Entries (recent / unique)

### [{date}] {title}
...
```

**排序**：原则在前（最有价值），然后是摘要，最后是近期活跃条目。

### 步骤 6: 更新 Distill 元数据

追加到 `harness/memory/README.md`（若不存在则创建）：

```markdown
## Distill History

| Date | Level | Files Processed | Entries Before | Entries After | Archived To |
|------|-------|-----------------|----------------|---------------|-------------|
| 2026-04-20 | standard | 3 | 67 | 28 | archive/2026-04-20-pre-distill/ |
```

### 步骤 7: 验证完整性

重写后验证：

1. [ ] 所有归档文件存在且与原始文件一致（字节级或行数校验）
2. [ ] 无条目丢失 — 每个原始条目要么在蒸馏后文件中，要么在归档中
3. [ ] 原则正确引用其来源条目
4. [ ] 文件格式为有效 markdown，标题一致
5. [ ] 总条目数：蒸馏后 + 归档 = 原始数量

报告最终状态：

```
✅ Memory distilled

| File | Before | After | Reduction |
|------|--------|-------|-----------|
| domain-api-gotchas.md | 47 entries / 380 lines | 18 entries / 160 lines | 62% |
| process-improvements.md | 12 entries / 96 lines | 12 entries / 96 lines | 0% (under threshold) |
| tool-environment-lessons.md | 8 entries / 64 lines | 8 entries / 64 lines | 0% (under threshold) |

Archive: harness/memory/archive/2026-04-20-pre-distill/
```

## 追踪文件压缩（可选扩展）

追踪文件（`harness/trace/*.md`）也可能增长。若超过 **20 个追踪文件**：

1. 将 60 天前的追踪归档到 `harness/trace/archive/`
2. 创建 `harness/trace/summary.md`，包含所有已归档追踪的表格：
   ```
   | Date | Task | Status | Key Outcome |
   |------|------|--------|-------------|
   | 2026-01-15 | user-api-feature | completed | Added pagination |
   ```
3. 保留近期追踪 — 这些是 Agent 正在主动引用的

## 禁止事项

- 严禁在归档前删除条目
- 严禁合并描述不同根本原因的条目（即使症状相似）
- 严禁在不展示合并计划的情况下进行蒸馏
- 严禁修改 memory 文件顶部的 Format 章节
- 严禁对少于 20 条目的文件进行蒸馏（风险大于收益）
- 严禁在文件正被其他技能写入时运行

## 与其他技能集成

- **harness-executor** Step 1：若 memory 文件较大（>50 条目），建议先运行 memory-distill
- **harness-executor** Step 8：写入新 memory 条目后，检查是否达到蒸馏阈值
- **commit-review**：memory distill 的更改应以 `chore: distill harness memory` 提交

## 完成标准

- [ ] 步骤 0 状态表格输出，所有文件条目/行数已记录
- [ ] 步骤 1 归档目录已创建，包含所有 memory 文件的精确副本
- [ ] 步骤 2 合并计划已展示并获得确认
- [ ] 步骤 3 原则提炼正确（standard/deep 级别），[PRINCIPLE] 格式完整且引用正确
- [ ] 步骤 5 重建文件结构符合模板（Principles / Monthly Summaries / Active Entries 三章节保留英文）
- [ ] 步骤 6 README.md 的 Distill History 表格已追加新行
- [ ] 步骤 7 完整性检查全部通过（5 项 checklist 均为 true）
- [ ] `harness/memory/index.yaml` 已同步新增/修改/归档条目
- [ ] `python scripts/lint_memory.py` 通过
- [ ] 无任何禁止事项违规

## 输出产物

- `harness/memory/archive/{YYYY-MM-DD}-pre-distill/` — 蒸馏前的原始 memory 文件归档
- `harness/memory/domain-api-gotchas.md`（重建）— 去重/聚类/原则化后的版本
- `harness/memory/process-improvements.md`（重建）— 同上
- `harness/memory/tool-environment-lessons.md`（重建）— 同上
- `harness/memory/failure-patterns.md`（重建）— 重复失败模式和预防 playbook
- `harness/memory/decisions.md`（重建）— harness memory / agent-operation 决策
- `harness/memory/index.yaml`（更新）— 每个 durable memory id 的索引和生命周期状态
- `harness/memory/README.md` — Distill History 表格新增一行
- 终端状态报告 — Before/After 对比表格和归档路径

## 错误处理

- **memory 文件被其他技能占用** → 停止并报告 "Memory file is currently being written to by another skill. Please retry after the other operation completes."
- **archive 目录已存在同日条目** → 使用更高精度时间戳（如 `{YYYY-MM-DD-HH-mm}`）创建新归档，避免覆盖已有内容
- **完整性检查失败** → 停止并显示具体失败的检查项，从归档恢复原始文件，禁止部分写入导致数据不一致
- **合并计划被用户拒绝** → 停止蒸馏，保留原始文件，向用户确认是否需要调整合并策略后重试
- **蒸馏后文件反而更大** → 停止，报告 "Distillation increased file size. Aborting to preserve original."，不执行任何写入操作
- **`lint_memory.py` 失败** → 停止，修复 index/entry/evidence 漂移后再提交
