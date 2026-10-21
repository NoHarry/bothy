# Memory Governance

Harness memory is intentionally file-based. The repository remains the source of truth; any future embedding or vector index is only a retrieval cache built from committed files.

## Model

```text
trace              raw task facts
agent-runtime      local run state, handoff, validation and candidates
harness-dream      read-only reflection report
memory             reviewed durable lessons
repo evals         reviewed regression metadata
hardening_debt     owner-confirmed follow-up queue
scripts/hooks      mechanically enforced rules
```

## Durable Memory Requirements

Every durable memory entry that agents should reuse needs:

- stable id: `mem-YYYY-MM-DD-NNN`
- `Type`: `semantic`, `procedural`, `failure`, `reflective`, `policy`, or `decision`
- `Scope`: `template`, `project`, or `local`
- `Status`: `candidate`, `active`, `superseded`, `deprecated`, or `promoted_to_rule`
- `Confidence`: `low`, `medium`, or `high`
- evidence paths that can be checked

`local` memory must stay under ignored `harness/.local/`; it must not enter durable repo memory.

## Index

`harness/memory/index.yaml` is a lightweight retrieval and audit index. It is not a database. It tells agents which file to read and tells review tools whether memory entries have valid scope, status, confidence, and evidence.

Run:

```bash
python scripts/lint_memory.py
```

The linter checks:

- every indexed id exists in the referenced Markdown file
- every Markdown `mem-*` id exists in `index.yaml`
- enum values are valid
- local-only memory is not committed
- file evidence exists

## Dream And Distill

`harness-dream` is read-only. It may emit candidate memory with evidence, confidence, and `recommended_next_skill`, but it must not edit durable memory.

`memory-distill` is the rewrite path. It must:

1. show a merge plan
2. archive originals under `harness/memory/archive/`
3. update Markdown entries and `index.yaml`
4. run `python scripts/lint_memory.py`
5. submit through `commit-review`

## Runner Integration

Runner artifacts, Codex events, validation logs, and task summaries are raw material. They may generate memory candidates in local runtime folders or dream reports, but they must not be written directly into `harness/memory/`.

Runtime candidates live under `harness/.local/agent-runtime/runs/{run_id}/memory-candidates.yaml`. They can be used as evidence in a merge plan, but only `memory-distill` or an explicit Harness task may promote them into durable memory and update `harness/memory/index.yaml`.

Runtime eval candidates follow the same rule: `eval-candidates.yaml` is a proposal, while `harness/evals/` is a reviewed repo asset.
