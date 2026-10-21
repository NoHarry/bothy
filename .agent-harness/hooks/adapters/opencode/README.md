# OpenCode Adapter

OpenCode can consume project rules and skills through its compatible project configuration and plugin layer.

First-version adapter behavior:

- keep `AGENTS.md` as the canonical rule source
- project `.opencode/skills/` may be checked by `scripts/sync_skills.py`
- an OpenCode plugin can call `scripts/hook_bus.py`
- pass `--tool opencode`
- start in dry-run or record-only mode

Example:

```bash
python scripts/hook_bus.py --event stop --tool opencode --payload "{}" --dry-run
```
