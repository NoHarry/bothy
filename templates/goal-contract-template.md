# Goal Contract — {FEATURE_OR_WORKSTREAM}

> This file defines what "done" means for one feature, PRD, migration workstream, or long-running maintenance stream.
> `BLACKBOARD.md` tracks tasks, but only the result counters below count as real progress.

Default path: `doc/features/{feature}/outputs/goal-contract.md`.

## 0. Scope

- Project kind: `{greenfield | migration | maintenance | refactor | research}`
- Feature/workstream: `{feature or workstream name}`
- Source PRD/review: `doc/features/{feature}/outputs/product-spec-review.md`
- Progress ledger: `BLACKBOARD.md`
- No-progress round limit: `2`

## 1. 唯一目标

{State the single outcome this requirement or workstream must achieve. Use product/user outcome language, not framework or planning language.}

Examples:
- Greenfield: users can complete `{core workflow}` end to end on `{platform}` with real integrations and validation evidence.
- Migration: `{old capability}` is served by `{new system}` with no remaining production dependency on `{legacy component}`.

## 2. 终态判定

All checked items must be true before this requirement or workstream can be called complete:

- [ ] `{Core user path / migrated domain 1}` is working with validation evidence.
- [ ] `{Core user path / migrated domain 2}` is working with validation evidence.
- [ ] Real integration points replace temporary mock/stub/bridge paths required for this requirement.
- [ ] Required validation, replay, acceptance, or owner gates have concrete evidence.
- [ ] No hard-ban item in section 5 remains open.

## 3. 结果计数

Update the matching table in `BLACKBOARD.md` at the end of every working round.
Only these counters moving in the stated direction counts as progress.
New checklist rows, scaffolding, planner output, auditor output, review packages, or evidence bundles do not count unless one of these counters moves.

| Counter | Direction | Current | Target | Counts only when |
|---------|-----------|---------|--------|------------------|
| Core user paths complete | up | `{0}` | `{N}` | The path runs end to end and has validation evidence. |
| Acceptance criteria passed | up | `{0}` | `{N}` | The PRD/spec criterion has a concrete passing check. |
| Real integrations enabled | up | `{0}` | `{N}` | Mock/stub/bridge code is replaced by the real integration for this requirement. |
| Mock/stub/bridge dependencies remaining | down | `{N}` | `0` | A temporary path is removed or no longer used by the target workflow. |
| P0 blockers open | down | `{N}` | `0` | A blocker is fixed, owner-closed, or explicitly descoped. |

Migration projects may replace the rows above with native coverage, legacy bridge remaining, oracle replay parity, provider coverage, or owner gate closure counters.
Greenfield projects should keep user paths, acceptance criteria, real integrations, mock/stub remaining, and P0 blockers unless the PRD defines better outcome counters.
Maintenance projects should replace the rows with open P0/P1 bugs, regressions introduced, core smoke paths passing, flaky tests open, and unsupported mocks/stubs in shipped paths.

## 4. 固定执行循环

A working session reads context once, then loops batches until a stop condition.

Session start (once per session, not per batch):

1. Read this contract, `BLACKBOARD.md` result coverage, and the relevant PRD/spec/source code.
2. Resume the previous runtime handoff if one exists.

Per batch:

1. Pick one result counter and a batch of similar units that move it — units sharing the same
   pattern, client, or base abstraction. Default batch size `{8-15}` units; a smaller batch is
   allowed when the remainder is short or a unit is unusually complex, and the ledger row must
   state why.
2. Extract the shared semantics of the batch once; record only per-unit differences.
3. Implement the product or migration result, not a planner or report substitute.
4. Run the narrow validation that proves the counter changed. Long builds/tests may run in the
   background only if the worktree being validated stays unchanged; while waiting, do read-only
   analysis for the next batch, or use an isolated worktree/snapshot before editing.
5. Merge expensive environment operations (device installs, environment swaps, baseline capture
   cycles) into one cycle per session covering every batch that needs it, not one cycle per batch.
6. Update `BLACKBOARD.md` result coverage and task status; keep the changelog row for the batch
   to 3 lines or fewer, and append to one session trace instead of creating a trace file per batch.
7. Create a local checkpoint for the batch through commit-review checkpoint mode, then start the
   next batch immediately. Publish mode runs later and reviews the accumulated diff before push/MR.

Stop only when the current group/phase is complete, context is nearly exhausted, or the
no-progress rule in section 6 triggers. On stop, record validation evidence and write the
runtime handoff (next batch start point, unfinished items, environment state) before ending.
The smallest-vertical-slice rule still applies inside a batch: every batch must move a result
counter; batching amortizes ceremony, it does not change the definition of progress.

## 5. 硬性禁止

- Do not use planner, auditor, evidence-package, scaffold, or infrastructure-only work as a substitute for result counter movement.
- Do not turn owner/human gates into work items. Record the missing owner decision once, then return to the main loop or stop if blocked.
- Do not weaken tests, acceptance criteria, golden fixtures, reference baselines, replay oracles, or assertions to manufacture progress. The reverse duty also holds: when required replay/baseline inputs do not exist yet and the reference system is reachable, capturing them from the reference (with a trace) is the standard, required extension path — it is not an oracle modification.
- Hard bans and freezes apply to their stated scope only. Replay plans, request lists, and fixtures are data, not tools; external repositories are out of scope unless explicitly named. Do not extend a ban beyond its written scope to justify skipping required work.
- Do not declare completion until every item in section 2 is checked and every section 3 counter reaches its target.
- Project-specific hard bans: `{package names, signatures, mock paths, unsafe operations, naming bans, or other local rules}`.

## 6. 受阻处理

An evidence gap is not a blocker. When the selected slice lacks replay corpus, baseline rows,
oracle entries, or a group runner, but the reference system (old build, reference
implementation, golden environment) is reachable:

1. Author the missing inputs from the reference spec or source schemas.
2. Capture the reference behavior as the baseline and record a trace.
3. Diff the new implementation against the captured baseline and continue.

A skip/PENDING marker is valid only when the reference environment is physically unavailable,
and the marker must state what to rerun after recovery. "No corpus / no runner" alone is never
a valid skip reason while the reference is reachable.

If no result counter changes for `2` consecutive working rounds:

1. Stop active work.
2. Add a blocking note of 10 lines or fewer to `BLACKBOARD.md` or the owner action board.
3. State the missing input, decision, environment, or dependency.
4. Do not fill the waiting time with new planner, auditor, scaffold, or evidence-only work.

## 7. 每轮自检

Before ending a round, answer these three questions in the handoff or final report:

1. Which result counter changed, and by how much?
2. Did this round add planner/auditor/evidence/scaffold work that did not move a counter?
3. Did any validation, oracle, golden fixture, baseline, or acceptance condition get weakened?

## 8. 生命周期与变更规则

`goal-contract.md` is the contract for this requirement/workstream, not a permanent project identity.
Do not edit this file during normal rounds to make the work easier.

Allowed change triggers:

- Requirement or phase completion: every terminal checklist item is checked and every result counter reaches its target.
- Owner goal change: the owner explicitly changes scope, priority, or terminal conditions, and the decision is recorded.
- Main workstream switch: the project moves from greenfield buildout to migration, feature delivery, stabilization, maintenance, incident response, or another phase.
- Blocked goal: after the no-progress rule triggers and owner decides to narrow, replace, or pause the current goal.

Required change handling:

1. Record the trigger, owner/decision source, date, and reason in this section or the project changelog.
2. Archive or summarize the previous phase result before replacing counters.
3. Update `BLACKBOARD.md` result coverage to match the new counters and keep the related row linked to this file.
4. Run `python scripts/lint_goal_contract.py --file doc/features/{feature}/outputs/goal-contract.md`.

Recommended maintenance counters after feature delivery or migration completes:

| Counter | Direction | Current | Target | Counts only when |
|---------|-----------|---------|--------|------------------|
| Open P0/P1 bugs | down | `{N}` | `0` | A confirmed high-priority bug is fixed, owner-closed, or descoped. |
| Regressions introduced this phase | down | `{N}` | `0` | A regression is fixed and covered by validation. |
| Core smoke paths passing | up | `{0}` | `{N}` | A shipped core path passes the agreed smoke/regression check. |
| Flaky tests open | down | `{N}` | `0` | A flaky test is fixed, deleted with replacement coverage, or owner-quarantined. |
| Unsupported mocks/stubs in shipped paths | down | `{N}` | `0` | A shipped-path temporary dependency is removed or replaced. |

## 9. 开轮 Prompt 模板

Use this at the start of a fresh run:

```text
Read AGENTS.md, this feature goal-contract.md, CONSTRAINTS.md, WORKFLOW.md, harness.yaml, and BLACKBOARD.md result coverage once at session start; between batches consult only deltas.
Pick one result counter from goal-contract.md section 3.
Work in batches of similar units (default {8-15}; smaller only with a stated reason in the ledger row).
For each batch: extract shared semantics once, implement, validate; if long builds run in the background, do only read-only next-batch preparation unless an isolated worktree/snapshot is used. Update BLACKBOARD.md result coverage (changelog row ≤3 lines), create a commit-review checkpoint, then start the next batch immediately.
Merge expensive environment operations (installs, environment swaps, baseline captures) into one cycle per session.
If replay/baseline inputs are missing and the reference system is reachable, capture the baseline from the reference with a trace instead of recording a skip; skip/PENDING is valid only when the environment is physically unavailable and must state what to rerun after recovery.
Stop only when the group is complete, context is nearly exhausted, or the no-progress rule triggers; then write the handoff with the next batch start point.
Do not create planner/auditor/evidence/scaffold work unless it directly moves the selected counter.
```
