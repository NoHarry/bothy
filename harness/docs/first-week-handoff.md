# First Week Handoff

给负责人和新接手开发者的最短操作流程。

## Day 0: Developer Workspace

```bash
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
```

开发者处理 `SELF-FIX`，负责人处理 `OWNER-ACTION`。

## Day 1: Context Review

1. 阅读 `README.md`、`AGENTS.md`、`CONSTRAINTS.md`、`BLACKBOARD.md`。
2. 查看最近 5 条提交和 `harness/trace/` 最近记录。
3. 查看 `hardening_debt` 和 owner action board。

## Day 2-3: Harden What Is Known

1. 已确定的模块边界写入 `harness.yaml architecture.layers`。
2. 路径和 import 包名不一致时补 `import_prefix`。
3. 高频评审规则写入 `.review/rules/`。
4. 同步 `AGENTS.md`、`CONSTRAINTS.md`、`README.md`。

## Day 4-5: Stabilize Flow

1. 使用 `commit-review` 提交所有改动。
2. 对不能确认的规则，补 owner action board 或 `hardening_debt`。
3. 负责人决定 hook profile 是否从 `bootstrap` 升到 `baseline`。

## Definition Of Ready

- 新开发者运行 workspace-doctor 后没有 SELF-FIX。
- OWNER-ACTION 都有负责人和下一步。
- 提交路径只剩 `commit-review`。
- 业务/模块变化会触发 harness sync 检查。
