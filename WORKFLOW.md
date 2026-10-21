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
  feature:
    default_skill: prd-to-design
    autonomy_level: L2
    validation_profile: standard
    require_plan_review: true
  test:
    default_skill: code-to-test
    autonomy_level: L3
    validation_profile: standard
    require_plan_review: false
  refactor:
    default_skill: harness-executor
    autonomy_level: L3
    validation_profile: standard
    require_plan_review: true
  migration:
    default_skill: harness-executor
    autonomy_level: L2
    validation_profile: strict
    require_plan_review: true
  harness-upgrade:
    default_skill: harness-upgrade
    autonomy_level: L0
    validation_profile: strict
    require_plan_review: true
  external-validation:
    default_skill: harness-executor
    autonomy_level: L1
    validation_profile: strict
    require_plan_review: true

validation_profiles:
  standard:
    preflight:
      - python scripts/sync_skills.py --check
      - python scripts/lint_agent_entrypoints.py
      - python scripts/validate_workflow.py
    post_agent:
      - python scripts/validate.py --dry-run
    before_publish:
      - python scripts/validate.py --dry-run
  strict:
    preflight:
      - python scripts/sync_skills.py --check
      - python scripts/lint_agent_entrypoints.py
      - python scripts/validate_workflow.py
      - python scripts/validate_harness_sync.py
    post_agent:
      - python scripts/validate.py
    before_publish:
      - python scripts/validate.py

workspace_hooks:
  after_create:
    - python scripts/workspace_doctor.py --run-checks
  before_run:
    - python scripts/agent_runtime.py next --json
    - python scripts/sync_skills.py --check
  after_run:
    - python scripts/validate_harness_sync.py

human_gates:
  protected_paths:
    - .agent-harness/skills/src/**
    - .agents/skills/**
    - .claude/skills/**
    - .opencode/skills/**
    - scripts/validate.py
    - scripts/validate_workflow.py
    - harness.example.yaml
    - harness.yaml
    - WORKFLOW.md
  require_human_for:
    - policy_change
    - validation_change
    - deployment_change
    - credential_handling
    - external_device_destructive_action

external_validation:
  project_specific_smoke:
    mode: advisory
    suite: owner-defined
---

# Workflow Prompt

When working in this repository:

1. Read root `AGENTS.md` for the project map.
2. If the current BLACKBOARD item, PRD output, or handoff links `doc/features/{feature}/outputs/goal-contract.md`, read that file before planning; use its terminal checklist and result counters as the definition of progress for this requirement.
3. Read the nearest local `AGENTS.md` for the directory being changed; local maps refine but cannot relax root rules.
4. Read `CONSTRAINTS.md` for hard project rules.
5. Read `harness.yaml` or `harness.example.yaml` for validation and architecture configuration.
6. Use the selected task type from this workflow contract to choose the default skill and validation profile.
7. Before creating a new plan, inspect resumable local handoff with `python scripts/agent_runtime.py next --json`; resume a matching run instead of starting from zero.
8. Follow the selected skill workflow, including runtime notes when `scripts/agent_runtime.py` is available.
9. Before presenting a requirement preview, plan, research summary, or review conclusion, record a first-principles reflection with `agent_runtime.py workpad --reflection`: restate the user outcome, immutable constraints, smallest useful deliverable, evidence, risks, and what the plan/review would change after that check.
10. During active work, append plan, reflection, acceptance, validation, notes, or confusions to local workpad with `agent_runtime.py workpad`.
11. For work bound to a feature/workstream goal contract, end every round by reporting which result counter moved, old value, new value, and validation evidence. Planner, auditor, evidence-package, scaffold, or infrastructure-only work cannot be reported as primary progress unless a goal counter moved. For repetitive unit streams, plan in batches of similar units per the goal contract's session loop and keep session-level ceremony (context loading, final reports) once per session, not once per batch. Use commit-review checkpoint mode for local batch checkpoints; use publish mode only when preparing push/MR/交付.
12. Treat owner/human gates as decisions, not work items. Record the missing decision once and return to the main loop, or stop with a blocker when no result counter can move. A missing replay corpus or baseline is not an owner gate: when the reference system is reachable, capture the baseline from it with a trace instead of recording a skip; skip/PENDING is valid only when the environment is physically unavailable.
13. For plan, research, review, partial implementation, or interrupted work, update local handoff with `agent_runtime.py handoff` or `agent_runtime.py pause` before stopping.
14. Treat completion bar warnings as advisory; do not bypass validation, but do not block checkpoint/WIP commits solely because local completion bar has pending items.
15. Run the selected validation profile unless the task is explicitly documentation-only and the profile allows a narrower check.
16. Treat `WORKFLOW.md` as a repository execution contract, not as a replacement for `AGENTS.md`, feature `goal-contract.md`, `CONSTRAINTS.md`, or `harness.yaml`.
17. Do not bypass validation, local hooks, project review rules, or owner-required human gates.
18. If required information is missing, produce a Human Action Required note instead of guessing project policy.

`WORKFLOW.md` may describe repository-level execution preferences. It must not define platform runtime settings, credentials, branch protection overrides, or review bypasses.
