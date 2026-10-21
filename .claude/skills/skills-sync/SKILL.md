---
name: skills-sync
description: Manage Harness skills through the canonical .agent-harness source and project them to Codex, Claude Code, and OpenCode. Use when adding, deleting, renaming, or editing skills, or when checking/fixing skill projection drift.
license: MIT
metadata:
  author: noharry
  team: infra
  version: "0.1.0"
---

# Skills Sync

Use this skill when changing Harness skills or repairing projection drift.

## Rules

- Edit skill source only under `.agent-harness/skills/src/<skill>/`.
- Template-owned skills are declared in `.agent-harness/skills/registry.yaml`.
- Target project-only skills are declared in optional `.agent-harness/skills/registry.project.yaml`.
- A project registry must not redefine a template skill name; use project-only names or a project workflow overlay.
- Project-only skills are not Codex-only by default; set each supported projection explicitly under `targets`.
- Do not directly edit `.agents/skills/`, `.claude/skills/`, or `.opencode/skills/`.
- Projection directories are generated outputs.
- Commit both canonical source and generated projections.

## Add Or Update A Skill

1. Create or edit:

   ```text
   .agent-harness/skills/src/<skill-name>/SKILL.md
   ```

2. If this is a new template skill, add it to:

   ```text
   .agent-harness/skills/registry.yaml
   ```

   If this is a target project-only skill, add it to:

   ```text
   .agent-harness/skills/registry.project.yaml
   ```

3. Generate projections:

   ```bash
   python scripts/sync_skills.py --write
   ```

4. Check drift:

   ```bash
   python scripts/sync_skills.py --check
   ```

5. Update `AGENTS.md` and `README.md` when the skill inventory changes.

## Delete A Skill

1. Remove the entry from `.agent-harness/skills/registry.yaml` or `.agent-harness/skills/registry.project.yaml`.
2. Remove `.agent-harness/skills/src/<skill-name>/`.
3. Remove generated projection directories for that skill.
4. Run:

   ```bash
   python scripts/sync_skills.py --check
   ```

## Targeted Projection

Use targeted commands for troubleshooting:

```bash
python scripts/sync_skills.py --write --target claude
python scripts/sync_skills.py --check --target codex
```

## Validation

Before submitting:

```bash
python scripts/sync_skills.py --check
python scripts/validate_harness_sync.py
python scripts/lint_template_clean.py
```
