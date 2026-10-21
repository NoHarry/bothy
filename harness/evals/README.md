# Eval Governance

`harness/evals/` stores reviewed repo-level eval metadata. Runtime eval evidence and candidates start under ignored `harness/.local/agent-runtime/runs/{run_id}/` and are promoted here only after owner review or an explicit Harness task.

## Layers

| Layer | Location | Purpose |
|------|----------|---------|
| Runtime eval | `harness/.local/agent-runtime/runs/{run_id}/eval.json` | Evidence for one task run |
| Eval candidates | `harness/.local/agent-runtime/runs/{run_id}/eval-candidates.yaml` | Proposed future regression checks |
| Repo eval | `harness/evals/` | Reviewed eval suites and cases |

## Rules

- Worker agents may write runtime eval and eval candidates.
- Worker agents must not modify repo evals unless the task envelope explicitly allows it.
- Dream reports may suggest eval candidates but must not publish repo evals.
- Repo eval changes go through `commit-review`.

