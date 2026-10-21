# 执行追踪

每次任务执行生成一个追踪文件。记录所做的工作、做出的决策和结果。
文件命名：`{YYYY-MM-DD}-{task-short-name}.md`

## 目的

- Agent 操作的审计跟踪
- 重放并从过去的执行中学习
- 识别重复模式以实现自动化（轨迹编译）

## 追踪模板

每个追踪文件必须遵循此结构：

---
# 追踪：{任务简称}
- **日期**：{YYYY-MM-DD}
- **使用的技能**：{skill name}
- **状态**：{in-progress | completed | failed | rolled-back}

## 目标
{目标是什么？}

## 执行步骤
1. {步骤描述} — {结果：success | failed | partial}
2. ...

## 做出的决策
- {决策}：{理由}

## 验证结果
- 构建：{pass/fail}
- 架构检查：{pass/fail}
- 测试：{pass/fail - N passed, M failed}

## 经验教训
- {任何新发现的坑、改进或工具经验}
- {参考：追加到 harness/memory/{appropriate-file}.md}

## 变更的文件
- {path/to/file} — {变更内容}
---

## 存储

将追踪文件放置在 `harness/trace/` 目录中。
追踪读取器（librarian agent）可以按日期、使用的技能或结果搜索和检索过去的追踪。

（文件结束 — 共 48 行）