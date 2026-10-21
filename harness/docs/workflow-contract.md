# Workflow Contract

`WORKFLOW.md` is a repository-owned execution contract for Harness agents. It tells an agent how this repository prefers to organize work: task types, default skills, validation profiles, workspace hooks, human gates, and external validation notes.

It is intentionally local to the repository. It does not configure an external orchestration service, runtime server, credentials, ports, global concurrency, branch protection, sandbox policy, or review bypasses.

## File Roles

| File | Role |
|---|---|
| `AGENTS.md` | Root project map and navigation entry point |
| Local `AGENTS.md` | Subtree-specific map that refines root rules |
| `CONSTRAINTS.md` | Hard rules and mechanical constraints |
| `harness.yaml` | Project configuration, platforms, validation, architecture, quality |
| `WORKFLOW.md` | Repository execution contract for task flow |

`WORKFLOW.md` may select or recommend validation profiles, but `harness.yaml`, local hooks, and review rules remain authoritative for what must pass.

## Supported Shape

`WORKFLOW.md` uses Markdown with YAML front matter:

```markdown
---
version: 1
name: default-harness-workflow
owner: infra

task_types:
  bugfix:
    default_skill: harness-executor
    autonomy_level: L4
    validation_profile: standard
    require_plan_review: false

validation_profiles:
  standard:
    preflight:
      - python scripts/sync_skills.py --check
    post_agent:
      - python scripts/validate.py --dry-run

workspace_hooks:
  before_run:
    - python scripts/sync_skills.py --check

human_gates:
  protected_paths:
    - WORKFLOW.md
  require_human_for:
    - validation_change
---

# Workflow Prompt

Read root AGENTS.md, the nearest local AGENTS.md for changed paths, CONSTRAINTS.md, harness.yaml, and this workflow before acting.
```

## Allowed Sections

- `task_types`: maps task categories to a default skill, autonomy level, validation profile, and plan-review setting.
- `validation_profiles`: named command groups for preflight, post-agent, and before-publish checks.
- `workspace_hooks`: repository-local lifecycle hooks. MVP hooks must call repository scripts through Python.
- `human_gates`: path and change categories that require owner review.
- `external_validation`: advisory metadata for project-specific external validation. Real device, CI, or credential mapping belongs outside this file.

## Preview Reflection

Before presenting a requirement preview, plan preview, research summary, or review conclusion, record a first-principles reflection in the runtime workpad when `scripts/agent_runtime.py` is available. The reflection should cover the intended user outcome, immutable constraints, smallest useful deliverable, evidence, risks, and any plan or review correction made after that check.

## Forbidden Content

Do not put any of the following in `WORKFLOW.md`:

- credential material, tokens, passwords, private keys, SSH keys, or API keys
- runtime roots, service commands, API ports, or global concurrency
- sandbox baseline changes
- branch protection overrides
- validation, policy, review, or publish bypass instructions

`scripts/validate_workflow.py` fails on forbidden front matter fields and unsafe hook commands.

## Validation

Run:

```bash
python scripts/validate_workflow.py
```

The validator checks:

1. front matter exists and parses
2. schema version is supported
3. task type skills exist in the skill registry or projections
4. validation profile references resolve
5. commands are non-empty and avoid compound shell operators
6. workspace hooks call repository scripts through Python
7. human gate path entries are non-empty
8. forbidden platform or credential fields are absent

Template repositories must include `WORKFLOW.md`. Target projects may keep using fallback workflows until they adopt this file, unless their owner chooses to require it.

## Upgrade Behavior

`WORKFLOW.md` is add-only during `harness-upgrade`: if a target project lacks it, the upgrade plan can add the template version. If the target already has a different file, the item goes to `MANUAL` and must be merged deliberately.
