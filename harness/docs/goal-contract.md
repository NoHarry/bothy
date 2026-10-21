# Goal Contract

`goal-contract.md` defines the outcome contract for one PRD, feature, migration workstream, stabilization phase, or other long-running requirement. It exists because task lists can drift: an agent can complete many valid planner, auditor, scaffold, or evidence tasks without moving the product or migration outcome.

The contract is intentionally not a root `harness.yaml` field. `harness.yaml` is stable mechanical configuration; active goals are task state and should live beside the requirement that created them.

## Location

Default location:

```text
doc/features/{feature}/outputs/goal-contract.md
```

The corresponding `product-spec-review.md` or `BLACKBOARD.md` row should link this path. Short one-off bugfixes and small maintenance tasks do not need a goal contract unless they are part of a larger tracked workstream.

Run the mechanical check:

```bash
python scripts/lint_goal_contract.py
python scripts/lint_goal_contract.py --file doc/features/{feature}/outputs/goal-contract.md
```

By default, the lint scans `doc/features/**/outputs/goal-contract.md`. If no contracts exist, it skips cleanly. The lint requires existing contracts to include required sections, use checklist items for terminal acceptance, and include a result counter table with progress direction.

## Greenfield Projects

For a new project, counters should describe product readiness:

| Counter | Direction | Meaning |
|---------|-----------|---------|
| Core user paths complete | up | A user-visible workflow runs end to end. |
| Acceptance criteria passed | up | PRD/spec criteria have concrete validation evidence. |
| Real integrations enabled | up | API, database, auth, device, payment, or other real dependencies are connected. |
| Mock/stub dependencies remaining | down | Temporary behavior is removed or replaced. |
| P0 blockers open | down | Issues blocking the first usable product are closed or descoped by owner decision. |

Scaffolding can be necessary, but it is not the progress metric. A greenfield round should pick one user path or acceptance criterion, implement the smallest vertical slice, validate it, and update the counter.

## Migration Projects

For a migration or strangler-style rewrite, counters should describe movement away from the old system:

| Counter | Direction | Meaning |
|---------|-----------|---------|
| Native/new-system coverage | up | A domain, API, tool, screen, or workflow is served by the new system. |
| Legacy bridge remaining | down | Production calls through the old path are removed. |
| Oracle/replay parity passed | up | Reference behavior is matched without weakening the oracle. |
| Real provider/resource coverage | up | Placeholder or config-only behavior is replaced with real runtime behavior. |
| Owner gates closed | up | External decisions close with explicit owner evidence. |

Do not generalize project-specific rules into the template. Package names, signatures, uninstall/install procedures, device bench names, or local naming bans belong in that feature/workstream contract's hard-ban section.

## Maintenance Projects

After feature delivery or migration completes, the active work may no longer need a goal contract. Create or update a maintenance/stabilization contract only when the bugfix stream is long-running or drift-prone. This is still a goal contract, but the target is operational stability rather than new feature completion.

Useful maintenance counters:

| Counter | Direction | Meaning |
|---------|-----------|---------|
| Open P0/P1 bugs | down | Confirmed high-priority bugs are fixed, owner-closed, or descoped. |
| Regressions introduced this phase | down | Regressions are fixed and covered by validation. |
| Core smoke paths passing | up | Shipped critical paths continue passing agreed checks. |
| Flaky tests open | down | Flaky tests are fixed, replaced, or owner-quarantined. |
| Unsupported mocks/stubs in shipped paths | down | Temporary behavior is removed from shipped paths. |

Small bugfixes do not need to create or edit `goal-contract.md`. They update `BLACKBOARD.md` task status and normal validation evidence. Edit a maintenance contract only when the maintenance workstream objective or counters change.

## Lifecycle Changes

`goal-contract.md` is a requirement/workstream contract, not a permanent project identity. Change it only at explicit boundaries:

- Requirement or phase completion: terminal checklist and result counters are complete.
- Owner goal change: scope, priority, or terminal conditions change by owner decision.
- Main workstream switch: the project moves from greenfield buildout to migration, feature delivery, stabilization, maintenance, incident response, or another phase.
- Blocked goal: the no-progress rule triggers and owner decides to narrow, replace, or pause the goal.

Every goal-contract change must record the trigger, owner/decision source, date, and reason; archive or summarize the previous phase in the contract changelog or related review output; update `BLACKBOARD.md` result coverage; and rerun `python scripts/lint_goal_contract.py`.

## Anti-Drift Rules

- Planner, auditor, evidence-package, scaffold, or infrastructure-only work cannot be primary progress unless a result counter moves.
- Owner gates are decisions, not work items. Record the missing decision once, then return to the main loop or stop with a blocker.
- Golden fixtures, replay oracles, reference baselines, assertions, and acceptance criteria may not be weakened to manufacture progress.
- If no result counter changes for the configured number of rounds, stop and write a short blocker instead of creating more meta work.

## Session Loop and Batching

Per-round fixed ceremony — context loading, full validation, review, trace, final report — is
expensive. When a round moves only one or two units, ceremony dominates and throughput collapses.
For repetitive unit streams (migration tool groups, bug backlogs, endpoint rewrites), the
contract's execution loop should define the working unit as a session of batches, not a single
smallest slice per round:

- Read the contract documents once per session; between batches consult only deltas.
- Batch similar units (same pattern, client, or base abstraction). A default batch size of
  8-15 units works well; require a stated reason whenever a batch is smaller.
- One local checkpoint per batch through commit-review checkpoint mode; publish mode later reviews
  the accumulated diff before push/MR.
- Run long builds/tests in the background only when the validated worktree remains unchanged.
  While waiting, do read-only next-batch preparation, or use an isolated worktree/snapshot before
  editing.
- Merge expensive environment operations (device installs, environment swaps, baseline capture
  cycles) into one cycle per session covering every batch that needs it.
- Cap ledger growth: changelog rows of 3 lines or fewer per batch, one trace file per session.
- Stop only on group/phase completion, near-exhausted context, or the no-progress rule, and end
  with a handoff naming the next batch start point so the next session resumes at zero cost.

The smallest-vertical-slice rule still applies inside a batch: every batch must move a result
counter. Batching amortizes ceremony; it does not change the definition of progress.

## Evidence Gaps and Skip Tiering

Field experience from migration workstreams: agents read prohibition-only clauses as "cannot"
and read peer-level escape clauses as defaults. Two authoring rules prevent both failure modes:

- Pair every anti-cheat clause with its positive duty. "Baselines may only change by recapture
  from the reference system" must be accompanied by "when inputs are missing and the reference
  system is reachable, capturing them is required" — otherwise a missing corpus reads as a dead
  end instead of a to-do, and the agent records a skip where the contract intended a capture.
- Never offer a skip reason as a peer alternative to the required action. Tier it instead:
  name the only condition under which skipping is legal (the reference environment is
  physically unavailable), require the skip record to state what must rerun after recovery,
  and make the capture-first path the required action everywhere else.

Scope precision belongs in the same review: every freeze or hard ban should name its exact
scope. Replay plans, request lists, and fixtures are data, not tools; external repositories
are out of scope unless explicitly named. An over-broad ban becomes the agent's justification
to self-block on work the contract actually requires.

## Import Isolation

Migration projects often need a temporary quarantine for old packages or SDKs. Configure it under `architecture.isolation`:

```yaml
architecture:
  isolation:
    - import_prefix: "com.legacy.runtime"
      allowed_paths:
        - "workspace/apps/android/legacybridge/**"
      note: "Temporary bridge during migration; terminal state should remove this rule."
```

`python scripts/lint_deps.py` fails if the import prefix appears outside the allowed paths. An empty `allowed_paths` list means the prefix is forbidden everywhere.
