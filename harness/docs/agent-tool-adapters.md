# Agent Tool Adapters

Harness should keep one project rule source and adapt it to multiple agent tools.

## Canonical Sources

- `AGENTS.md`: root project rules and navigation map
- Local `AGENTS.md`: component maps that refine root rules for their subtree
- `.agent-harness/skills/src/`: canonical skill source
- `.agent-harness/skills/registry.yaml`: template-owned skill registry
- `.agent-harness/skills/registry.project.yaml`: optional target-project skill registry extension
- `.agents/skills/`: Codex skill projection
- `.claude/skills/`: Claude Code skill projection
- `.opencode/skills/`: OpenCode skill projection
- `harness/policy.yaml`: optional target-project hook policy

## Tool Mapping

| Tool | Rules | Skills | Events |
|---|---|---|---|
| Codex | root `AGENTS.md` plus nearest local `AGENTS.md` | `.agents/skills/` | Codex hooks call `scripts/hook_bus.py` |
| Claude Code | thin `CLAUDE.md` importing `AGENTS.md`; local maps are read by path context | `.claude/skills/` | Claude hooks call `scripts/hook_bus.py` |
| OpenCode | root `AGENTS.md` or compatible project rules plus local maps | `.opencode/skills/` | OpenCode plugin calls `scripts/hook_bus.py` |

## First-Version Plan

1. Keep root `AGENTS.md` short and canonical.
2. Put component details in local `AGENTS.md` files near the owned subtree.
3. Add adapter documentation and policy examples.
4. Run `sync_skills.py --write` after source skill changes.
5. Run `hook_bus.py --dry-run` instead of blocking tool calls.
6. Let `harness-dream` analyze the resulting records later.

## Claude Code Note

Projects using Claude Code must keep `CLAUDE.md` as a thin adapter:

```markdown
@AGENTS.md

<!-- Harness adapter only. Do not add project rules here; update AGENTS.md. -->
```

Do not add project rules, preferences, workflows, or skill instructions to `CLAUDE.md`. `scripts/lint_agent_entrypoints.py` enforces this shape and, in template repositories, checks required framework-owned local `AGENTS.md` files.
