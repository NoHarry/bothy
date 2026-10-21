# Harness Agent Runtime Contract

Harness Agent Runtime Contract defines how a single worker agent records local execution state. It serves two modes with one schema:

- local mode: one agent or developer uses `current` and `handoff.md` to resume work.
- worker mode: an external runner can read the same files to inspect progress.

It is not a scheduler, daemon, PM agent, database, merge queue, or durable memory store.

## Runtime Root

Runtime files live under ignored `harness/.local/agent-runtime/`:

```text
harness/.local/agent-runtime/
  manifest.json
  current/
    run.json
  runs/
    {run_id}/
      run.json
      task.yaml
      state.json
      events.jsonl
      validation.json
      eval.json
      requests.jsonl
      workpad.json
      workpad.md
      handoff.json
      result.md
      handoff.md
      blockers/
      memory-candidates.yaml
      eval-candidates.yaml
      artifacts/
  archive/
```

`current/run.json` is only a pointer to the active run. The run directory is the source of truth.

## CLI

Use `scripts/agent_runtime.py`:

```bash
python scripts/agent_runtime.py init
python scripts/agent_runtime.py discover --json
python scripts/agent_runtime.py start --objective "fix bug" --json
python scripts/agent_runtime.py start --objective "write implementation plan" --task-type plan --role planner --json
python scripts/agent_runtime.py next --json
python scripts/agent_runtime.py current --json
python scripts/agent_runtime.py resume --json
python scripts/agent_runtime.py workpad --plan "Implement step 1"
python scripts/agent_runtime.py workpad --reflection "From first principles: preserve user outcome, constraints, and smallest useful preview before changing the plan."
python scripts/agent_runtime.py workpad --acceptance "resume shows pending work"
python scripts/agent_runtime.py workpad --validation "python scripts/validate.py"
python scripts/agent_runtime.py workpad --note "Investigation finished"
python scripts/agent_runtime.py event --type note --message "implementation started"
python scripts/agent_runtime.py validation --command "python scripts/validate.py --dry-run" --status pass
python scripts/agent_runtime.py eval --id required-validation --status pass
python scripts/agent_runtime.py request --type plan_approval --target owner --summary "Review implementation plan"
python scripts/agent_runtime.py respond --request-id req-YYYYMMDD-HHMMSS --status approved --feedback "Proceed"
python scripts/agent_runtime.py requests --status pending --json
python scripts/agent_runtime.py block --type BLOCKED_VALIDATION --message "tests fail"
python scripts/agent_runtime.py handoff --status planned --kind plan-to-execute --summary "Plan is ready" --next "Implement step 1"
python scripts/agent_runtime.py handoff --context "Why this exists" --decision "Use existing runtime schema" --constraint "Do not edit durable memory"
python scripts/agent_runtime.py pause --summary "Stopping with partial context" --next "Resume at failing test"
python scripts/agent_runtime.py check
python scripts/agent_runtime.py complete --status completed
python scripts/agent_runtime.py export-trace
```

All commands default to the current run when `--run-id` is omitted.

## External Runner Discovery

An external PM/runner should not guess paths. From the worker workspace root, run:

```bash
python scripts/agent_runtime.py discover --json
python scripts/agent_runtime.py status --json
python scripts/agent_runtime.py current --json
python scripts/agent_runtime.py resume --json
```

`discover` returns the contract version, runtime root, current pointer, runs directory, and archive directory. `next` lists resumable runs with workpad summaries. `resume` returns the task envelope, state, recent events, blockers, protocol requests, validation data, handoff JSON/content, and workpad JSON/content for the current run.

## Ambient Handoff

Agents should treat runtime handoff as ambient context, not a user-triggered chore:

- At session start, run `python scripts/agent_runtime.py next --json` before creating a new plan.
- If a resumable run matches the user's request, run `resume --run-id ... --json` and continue from its `workpad` and `handoff`.
- When producing a plan, research output, or partial implementation that another agent may consume, start or update a run with `--task-type plan|research|review` and write a handoff with `--status planned` or `--status paused`.
- Before presenting a requirement preview, plan, research summary, or review conclusion, record a first-principles reflection in `workpad`: user outcome, non-negotiable constraints, smallest useful deliverable, available evidence, risks, and any plan/review correction that follows.
- Use `workpad` to append plan, reflection, acceptance, validation, notes, and confusions while work is active.
- Use `request` / `respond` for explicit delivery handshakes such as plan approval, validation exception approval, handoff acceptance, or owner decisions. Requests are advisory runtime records until a separate policy makes a type blocking.
- Before stopping, handing back, or finalizing, update `handoff.json` and `handoff.md` with `handoff` or `pause`.
- `workspace-doctor` surfaces resumable runs as `RESUME-FIRST` so a returning developer or new agent sees unfinished context and pending completion items before normal next steps.

## Handoff Shape

`handoff.md` is written for a zero-context receiver. The next agent should be able
to continue without chat history by reading these sections:

- `Task`: the objective and exact work mode.
- `Context`: why the task exists and background that is not obvious from files.
- `Relevant Files`: files to read first, including touched files and any manually recorded paths.
- `Current State`: status, phase, summary, done items, next items, and workpad pointers.
- `What Was Tried`: approaches attempted and why they did not finish the task.
- `Decisions`: decisions already made and their rationale.
- `Acceptance Criteria`: task acceptance plus workpad acceptance checks.
- `Constraints`: task scope, forbidden paths, and handoff-specific must-preserve rules.
- `Protocol Requests`: recent request/response records and any unresolved delivery handshakes.
- `Open Questions`, `Risks`, `Validation Evidence`, `Evidence`, `Recent Events`, and `Do Not Repeat`.

The JSON payload remains backward compatible. Existing fields such as `summary`,
`done`, `next`, `open_questions`, `risks`, `do_not_repeat`, and `evidence` still
work. New optional fields are `context`, `relevant_files`, `tried`, `decisions`,
and `constraints`, populated by:

```bash
python scripts/agent_runtime.py handoff \
  --context "Why this task exists" \
  --relevant-file "scripts/agent_runtime.py" \
  --tried "Old approach - why it failed" \
  --decision "Decision - rationale" \
  --constraint "Must preserve existing CLI flags"
```

## Workpad And Completion Bar

`workpad.md` is the local continuation surface. It is ignored, generated per run, and should stay concise:

- `Environment`: workspace, branch, HEAD, run id.
- `Completion Bar`: advisory readiness checklist.
- `Plan`: current execution checklist.
- `Reflection`: first-principles checks recorded before requirement previews, plan previews, research summaries, or review conclusions.
- `Acceptance Criteria`: scope-specific criteria or recorded exceptions.
- `Validation`: required or planned validation commands.
- `Notes`: evidence and progress details.
- `Confusions`: unclear points discovered during execution.

Completion bar checks are advisory. `complete` and `commit-review` may warn on pending items, but they must not block checkpoint or WIP commits solely because the local completion bar is unfinished.

## State Machine

```text
created
running
planned
paused
blocked
needs_review
completed
failed
cancelled
archived
```

`planned`, `paused`, `blocked`, `needs_review`, and `running` are resumable states. `failed` and `blocked` are preserved states, not cleanup instructions.

## Protocol Requests

`requests.jsonl` is a local request/response ledger for structured delivery handshakes. It borrows the request-id pattern from team protocols without adding a message bus, daemon, or autonomous worker scheduler.

Each request contains:

- `request_id`: stable id generated as `req-YYYYMMDD-HHMMSS`
- `type`: request purpose, such as `plan_approval`, `handoff_acceptance`, `validation_exception`, `owner_decision`, or `worker_shutdown`
- `sender` and `target`: who asked and who should respond
- `status`: `pending`, `approved`, `rejected`, or `cancelled`
- `summary`, `payload`, and `evidence`: what is being requested and why
- `response`: responder, status, feedback, evidence, and response time when resolved

Open requests are surfaced by `show`, `resume`, `handoff.md`, `export-trace`, and `check`. They are warnings by default, not blockers, so checkpoint and WIP commits remain possible while still making unfinished approval or acceptance handshakes visible.

## Promotion Flow

Runtime data is temporary. Promote deliberately:

```text
runtime result/handoff -> export-trace -> harness/trace
runtime requests -> handoff/export-trace -> review notes or owner action
runtime memory-candidates -> memory-distill -> harness/memory
runtime eval-candidates -> owner review -> harness/evals
runtime blockers -> hardening_debt / owner action board
runtime repeated failures -> harness-dream report
```

## Boundaries

- Do not commit `harness/.local/agent-runtime/`.
- Do not treat runtime files as durable memory.
- Worker agents may write runtime eval and candidates.
- Worker agents must not modify durable memory, repo evals, policy, hooks, prompts, or review rules unless the task envelope explicitly allows it.
- Validation skip requires a reason in `validation.json` or result/handoff.
