# Harness Hook Adapters

This directory holds adapter guidance for agent-tool hook systems.

Adapters should translate native tool events into the Harness standard event model and call:

```bash
python scripts/hook_bus.py --event <event> --tool <tool> --payload "<json>"
```

The first version is documentation and dry-run plumbing only. Do not make adapters hard-blocking until the project owner approves the policy.
