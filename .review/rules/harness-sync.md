# Harness Sync Review Rules

## Rule HSR-01: commit-review is the only allowed submit entry

- Trigger: commit or push preparation skips `commit-review`.
- Severity: high
- Deduction: 30
- Required check: verify the diff was reviewed through `commit-review` and project `.review/rules/*.md`.

## Rule HSR-02: implementation changes must update harness context

- Trigger: source, module, build, workflow, or skill changes appear without related updates to `AGENTS.md`, `CONSTRAINTS.md`, `README.md`, `WORKFLOW.md`, `harness.yaml`, `harness/memory/`, `harness/trace/`, `doc/features/`, `docs/`, or `.review/rules/`.
- Severity: high
- Deduction: 25
- Required check: run `python scripts/validate_harness_sync.py`.

## Rule HSR-03: new modules must enter architecture lint

- Trigger: new source module, package, Gradle module, npm package, service, or protocol layer is added.
- Severity: high
- Deduction: 25
- Required check: `architecture.layers` must include `path`, `level`, and when path/import names differ, `import_prefix`.

## Rule HSR-04: uncertain constraints must become owner action

- Trigger: a rule is observed but cannot yet be safely hardened.
- Severity: medium
- Deduction: 15
- Required check: add an item to `hardening_debt` or an owner-facing action board with owner, reason, and next step.

## Rule HSR-05: commit log is not synchronization evidence

- Trigger: the only non-code artifact in a commit is `commit_log.csv`.
- Severity: medium
- Deduction: 15
- Required check: `commit-review` must not generate shared commit logs; legacy `commit_log.csv` content cannot replace harness context updates.

## Rule HSR-06: framework upgrades must be planned before applied

- Trigger: harness template files, scripts, skills, hooks, or docs are copied into an already-onboarded project without a `harness-upgrade` plan/report.
- Severity: medium
- Deduction: 15
- Required check: run `python {template}/scripts/upgrade_harness.py --source {template} --target {project} --mode plan --write-report` before applying updates.

## Rule HSR-07: durable memory must be indexed and evidenced

- Trigger: `harness/memory/*.md` changes add or modify a durable `mem-*` entry without updating `harness/memory/index.yaml`, or evidence paths do not resolve.
- Severity: medium
- Deduction: 15
- Required check: run `python scripts/lint_memory.py`.

## Rule HSR-08: target harness.yaml must stay trackable

- Trigger: `.gitignore` or bootstrap flow ignores `harness.yaml`, or template-local config is stored in `harness.yaml` instead of `harness.local.yaml`.
- Severity: high
- Deduction: 25
- Required check: template repositories use ignored `harness.local.yaml` for local self-maintenance; initialized target projects commit their generated `harness.yaml`.

## Rule HSR-08B: target quality baseline must stay trackable

- Trigger: `.gitignore` ignores `harness/baseline.json`, or a template repository stages `harness/baseline.json`.
- Severity: medium
- Deduction: 15
- Required check: target projects commit `harness/baseline.json` when using `baseline` or `drift_gate`; template repositories keep local quality baselines under `harness/.local/baseline.json`.

## Rule HSR-09: template version metadata must stay synchronized

- Trigger: template scripts, skills, docs, hooks, examples, or upgrade behavior change without checking whether `harness/VERSION`, `harness.example.yaml`, example configs, README Version History, and `harness-upgrade` docs need updates.
- Severity: medium
- Deduction: 15
- Required check: run `python scripts/lint_template_version.py`; for major template changes, update README Version History before commit.

## Rule HSR-10: runtime artifacts must not become durable assets directly

- Trigger: `harness/.local/agent-runtime/**` is staged, or runtime memory/eval candidates are copied directly into `harness/memory/` or `harness/evals/` without review evidence.
- Severity: high
- Deduction: 25
- Required check: runtime records stay ignored; durable memory goes through `memory-distill` and `python scripts/lint_memory.py`; repo eval additions must cite reviewed runtime evidence or owner approval.

## Rule HSR-11: workflow contract cannot lower safety gates

- Trigger: `WORKFLOW.md` adds credential fields, runtime/service settings, sandbox overrides, review bypasses, or unsafe workspace hook commands.
- Severity: high
- Deduction: 30
- Required check: run `python scripts/validate_workflow.py`; owner review is required for any validation profile, protected path, or human gate change.
