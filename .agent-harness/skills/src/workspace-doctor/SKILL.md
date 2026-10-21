---
name: workspace-doctor
description: Diagnose and repair safe local workspace setup for Harness projects. Use when a developer just cloned a repository, a new developer is taking over, someone returns after time away, local environment or git hooks may be stale, or the user says "我刚拉仓库", "新人接手", "一段时间没维护", "不知道缺什么", "workspace doctor", or asks what to read or do next before development.
---

# Workspace Doctor

Use this skill to orient a developer and fix safe local setup only. Do not implement features, refactor business code, or decide project policy for the owner.

## Workflow

1. Run the doctor from the repository root:

   ```bash
   python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
   ```

   With `--fix-safe`, the doctor may initialize ignored local runtime state with `python scripts/agent_runtime.py init`.

2. Interpret output by group:
   - `RESUME-FIRST`: inspect these local workpads/handoffs before starting a new plan or implementation; if one matches the user's request, resume it. Report pending completion and pending validation as reminders only.
   - `AUTO-FIXED`: confirm what was repaired locally.
   - `SELF-FIX`: tell the developer to fix local machine items such as missing tools, upstream, SDK path, or hooks.
   - `OWNER-ACTION`: hand to the project owner; do not guess.
   - `READ-FIRST`: point the developer to the listed project context.
   - `CHECKS`: summarize validation status.
   - `NEXT-STEPS`: state the immediate next safe action.

3. If JSON is needed for automation, run:

   ```bash
   python scripts/workspace_doctor.py --json
   ```

4. If `WORKFLOW.md` is present or required by the template, verify it:

   ```bash
   python scripts/validate_workflow.py
   ```

5. To inspect runtime state after the doctor:

   ```bash
   python scripts/agent_runtime.py status --json
   python scripts/agent_runtime.py next --json
   ```

## Boundaries

- Safe to install repository-local hooks through `scripts/install_git_hooks.py`.
- Safe to initialize `harness/.local/agent-runtime/` through `scripts/agent_runtime.py init`.
- Safe to read ignored runtime workpad/handoff through `scripts/agent_runtime.py next --json`.
- Safe to identify missing developer tools and stale repository state.
- Safe to validate `WORKFLOW.md` and report missing or unsafe workflow contract entries.
- Safe to write a diagnostic trace when `--write-trace` is requested.
- In template repositories, diagnostic traces are written under `harness/.local/trace/` instead of committed `harness/trace/`.
- Not safe to create PRDs, modify business code, change architecture decisions, or close owner actions without explicit human input.

## Android Projects

If the project is Android, require the developer to have Android CLI available as `android` on PATH and a configured SDK path through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `local.properties`.
