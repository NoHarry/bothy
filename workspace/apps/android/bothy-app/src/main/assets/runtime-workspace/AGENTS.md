# Debug Console Runtime Workspace

This workspace is provided by the sample host app through `workspace.json`.

## Filesystem
- `/AGENTS.md` is a real host-provided workspace file.
- `/.agents/skills/` is mounted from sample APK assets.
- `/memory/host/` is mounted from a host-managed in-memory source.
- `/context/debug-session-index/` is mounted from a host database-style source.
- `/sessions/` is reserved for legacy EventLog paths and must not be shadowed by Sample mounts.
- `/memory/` is writable runtime memory when the host maps it as read-write.
- `/scratch/` is run-scoped scratch space managed by the runtime tools.
- `/mcp/` and `/context/` may be backed by runtime-provided virtual sources or host mounts.

## Rules
- Read the smallest file or directory needed for the current task.
- Use backend tools when they are available in the current tool list.
- Call `finish(response)` exactly once when the useful result is ready.
