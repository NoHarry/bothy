# Harness Dream

`harness-dream` is the offline maintenance loop for a Harness project. It does not mean model training or autonomous self-improvement. It means reading the project records that already exist and producing an auditable report.

## First-Version Boundary

The first version is report-only:

- read trace, memory, local agent-runtime runs, hardening debt, and validation signals
- write one Markdown report
- never edit source, memory, evals, prompts, policies, or rules
- never submit commits

Template repositories write reports under `harness/.local/dream/reports/`. Target projects write reports under `harness/dream/reports/`.

Runtime inputs are read from ignored `harness/.local/agent-runtime/`. Dream may reference runtime result, handoff, validation, blocker, memory candidate, and eval candidate files as evidence, but it must not promote them automatically.

## Command

```bash
python scripts/dream_runner.py --limit 20 --write-report
```

Use `--json` when another script needs a machine-readable summary.

## When To Run

- after about 10 trace files
- after repeated validation failures
- after large manual commits with weak trace coverage
- after a template upgrade
- when memory files grow noisy
- before a project owner review

## Relationship To Memory Distill

`harness-dream` finds that memory may need cleanup. `memory-distill` performs the actual memory rewrite and must archive originals and show a merge plan first.

## Report Sections

- Signal summary
- Structured findings with evidence, confidence, and recommended next skill
- Notable signals
- Memory cleanup suggestions
- Memory candidates
- Hardening candidates
- Owner actions
- Missing validation evidence
- Boundaries

When runtime eval candidates look reusable, the recommended next step is an owner-reviewed Harness task that promotes them into `harness/evals/`, not a direct dream edit.

## Structured Findings

Each actionable finding should include:

- evidence: file and line or config path
- confidence: `low`, `medium`, or `high`
- recommended_next_skill: the next workflow to run, such as `memory-distill`, `harness-executor`, `code-to-test`, or `workspace-doctor`

Candidate memory remains a candidate until `memory-distill` or a normal Harness task approves it and updates `harness/memory/index.yaml`.

## Follow-Up Flow

Treat the report as a triage artifact:

1. turn project decisions into owner actions
2. run `memory-distill` only for approved memory cleanup
3. run normal Harness skills for code, tests, docs, or configuration changes
4. submit any resulting changes through `commit-review`
