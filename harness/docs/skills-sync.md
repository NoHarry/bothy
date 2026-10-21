# Skills Sync

`scripts/sync_skills.py` writes and checks skill projections across agent tools.

## Canonical Source

The canonical skill source is:

```text
.agent-harness/skills/src/<skill>/SKILL.md
.agent-harness/skills/registry.yaml
.agent-harness/skills/registry.project.yaml
```

`registry.yaml` is template-owned. Target projects that need additional, project-only skills use optional
`registry.project.yaml`; this keeps template upgrades from reporting the main registry as drift. A project registry
must not redefine a template skill name. If the function is the same, use the template skill and put project-specific
rules in repository docs or workflow overlays.

Project-only skills are not Codex-only by default. Declare the supported projections explicitly under `targets`.
When a project skill can be consumed by all supported agents, set `codex`, `claude`, and `opencode` to `true`;
disable a target only when the skill depends on an agent-specific command, adapter, or runtime contract.

Do not edit projection directories directly:

- `.agents/skills/`
- `.claude/skills/`
- `.opencode/skills/`

## Commands

```bash
python scripts/sync_skills.py --write
python scripts/sync_skills.py --check
```

`--write` replaces registered skill directories in the selected projection targets from the canonical source. `--check` fails when a projection is missing, drifted, or contains unmanaged skills.

Target one projection when needed:

```bash
python scripts/sync_skills.py --write --target claude
python scripts/sync_skills.py --check --target codex
```

## Source Layout

```text
.agent-harness/
  skills/
    registry.yaml
    registry.project.yaml
    src/
      <skill>/
        SKILL.md
        overlays/
          codex.md
          claude.md
          opencode.md
```

Projection targets:

- Codex: `.agents/skills/<skill>/SKILL.md`
- Claude Code: `.claude/skills/<skill>/SKILL.md`
- OpenCode: `.opencode/skills/<skill>/SKILL.md`

## Rule

Projection directories are generated artifacts. Local hooks run `python scripts/sync_skills.py --check`, so direct edits to projection directories are rejected unless the canonical source and registry are updated first.

Skill frontmatter metadata uses `author: noharry`; update it only in the canonical source and regenerate projections.
