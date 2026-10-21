# Codex Adapter

Codex projects use `AGENTS.md` as the canonical rule entry and `.agents/skills/` as the operational skill directory.

First-version adapter behavior:

- call `scripts/hook_bus.py` from Codex hook configuration when available
- pass `--tool codex`
- start in `--dry-run`
- keep hard enforcement in Git hooks and validation scripts

Example:

```bash
python scripts/hook_bus.py --event pre_tool_use --tool codex --payload "{}" --dry-run
```
