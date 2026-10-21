# Eval Governance

Harness evals have three layers:

| Layer | Location | Commit? | Purpose |
|------|----------|---------|---------|
| Runtime eval | `harness/.local/agent-runtime/runs/{run_id}/eval.json` | No | Evidence for one run |
| Eval candidate | `harness/.local/agent-runtime/runs/{run_id}/eval-candidates.yaml` | No | Proposed future regression case |
| Repo eval | `harness/evals/` | Yes | Reviewed reusable eval metadata |

## Runtime Eval

Runtime eval records task-specific validation and acceptance evidence. It can be written by a worker agent during normal execution.

## Eval Candidates

Bug fixes, repeated validation failures, and missing workflow gates may create candidates. Candidates are not active evals until reviewed.

## Repo Eval

Repo evals are project assets. They require owner review or an explicit Harness task and must pass `commit-review`.

The first template version provides metadata and governance only. A general eval runner can be added later.
