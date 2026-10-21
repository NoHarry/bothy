# Claude Code Adapter

Claude Code projects can keep project rules in `AGENTS.md` and use a thin `CLAUDE.md` that imports it.

Recommended `CLAUDE.md` shape:

```markdown
@AGENTS.md

## Claude Code

Use Harness skills, hooks, and policy adapters for this repository.
```

First-version adapter behavior:

- call `scripts/hook_bus.py` from Claude Code hooks
- pass `--tool claude`
- start in dry-run or record-only mode
- project-specific `CLAUDE.md` remains a protected/manual file
