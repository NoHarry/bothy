# Hook Bus

`scripts/hook_bus.py` is the Harness event normalization entry point for agent tools. Claude Code, Codex, and OpenCode have different native hook systems; Harness should receive a stable event shape.

## Standard Events

- `session_start`
- `user_prompt_submit`
- `pre_tool_use`
- `post_tool_use`
- `permission_request`
- `stop`
- `pre_compact`
- `post_compact`
- `session_idle`

## Command

```bash
python scripts/hook_bus.py --event session_start --payload "{}" --dry-run
```

Without `--dry-run`, events are appended to:

- template repository: `harness/.local/hook-events/{date}.jsonl`
- target project: `harness/trace/hook-events/{date}.jsonl`

## First-Version Boundary

The first version is dry-run or record-only. It does not block tool calls. Hard enforcement still belongs in Git hooks, validation scripts, CI, and commit review.

## Policy

`harness/policy.example.yaml` documents the expected policy shape. A target project may copy it to `harness/policy.yaml` and tune it. Template upgrades should not overwrite an active project policy.

## Tool Adapters

Adapters live under `.agent-harness/hooks/adapters/` and translate native tool payloads into the standard event model.
