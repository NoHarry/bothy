# Memory Governance

Harness memory is a repo-level engineering asset. It stays readable in Git, but each durable entry needs lifecycle, scope, and evidence so agents can decide what to trust and when to use it.

## Memory Layers

| Layer | Location | Purpose |
|------|----------|---------|
| Episodic | `harness/trace/` | Raw task history and validation evidence |
| Durable | `harness/memory/*.md` | Confirmed lessons, decisions, failure patterns, and procedures |
| Reflective | `harness/dream/reports/` or `harness/.local/dream/reports/` | Read-only dream reports and candidate follow-ups |
| Hardened | `scripts/`, `.githooks/`, `.review/rules/`, `harness.yaml` | Rules that can be mechanically enforced |

## Files

| File | Content |
|------|---------|
| `index.yaml` | Lightweight index for durable memory entries |
| `domain-api-gotchas.md` | Domain and API surprises |
| `process-improvements.md` | Harness and team workflow improvements |
| `tool-environment-lessons.md` | Tool, shell, IDE, CI, SDK, and local environment lessons |
| `failure-patterns.md` | Repeated failure modes and prevention playbooks |
| `decisions.md` | Harness memory and agent-operation decisions; business architecture decisions should use ADRs |
| `archive/` | Pre-distill snapshots and superseded material |

## Entry Schema

Use this structure for durable entries that should be indexed:

```md
## mem-YYYY-MM-DD-NNN - Short Title

Type: procedural
Scope: template
Status: active
Confidence: high
Tags: skills, projection
Evidence:
- scripts/sync_skills.py
- trace: harness/trace/2026-05-12-example.md

Lesson:
...

Use when:
- ...

Do not:
- ...

Hardening:
- ...
```

`scripts/lint_memory.py` checks that each indexed id exists in Markdown, each Markdown id exists in `index.yaml`, enum values are valid, local-only memory is not committed, and file evidence exists.

## Types

| Type | Meaning |
|------|---------|
| `semantic` | Stable project or domain fact |
| `procedural` | How to perform a workflow |
| `failure` | Repeated failure pattern and prevention |
| `reflective` | Lesson from dream or review |
| `policy` | Rule or boundary for agents |
| `decision` | Durable harness or agent-operation decision |

## Scopes

| Scope | Meaning |
|-------|---------|
| `template` | Applies to Harness template repositories and downstream projects |
| `project` | Applies only to the current target project |
| `local` | Applies only to one machine or user; must live under ignored `harness/.local/` |

## Statuses

| Status | Meaning |
|--------|---------|
| `candidate` | Proposed by dream, runner, or trace review; not yet trusted |
| `active` | Confirmed and safe for agents to use |
| `superseded` | Replaced by a newer entry, retained for provenance |
| `deprecated` | Known stale; do not use except for historical context |
| `promoted_to_rule` | Hardened into a script, hook, review rule, or config gate |

## Write Rules

- Do not write unverified candidate memory directly into durable memory files.
- Every durable entry needs evidence, confidence, scope, and status.
- Template repositories must keep runtime records under ignored `harness/.local/`.
- Local paths, credentials, machine-specific SDK paths, and personal preferences must not enter durable template memory.
- If a lesson can be mechanically checked, promote it to a script, hook, review rule, or `harness.yaml` gate.
- If a lesson needs owner confirmation, write an owner action or `hardening_debt` item before promoting it.

## Dream And Distill

`harness-dream` is read-only. It can produce memory candidates, hardening candidates, and owner actions, but it must not edit memory.

`memory-distill` is the only workflow that rewrites durable memory. It must first show a merge plan, archive originals, update `index.yaml`, run `scripts/lint_memory.py`, and submit through `commit-review`.

## Distill History

| Date | Level | Files Processed | Entries Before | Entries After | Archived To |
|------|-------|-----------------|----------------|---------------|-------------|
