# Memory Decisions

Durable decisions about Harness memory and agent-operation policy. Business architecture decisions should use ADRs instead of this file.

## Format

Indexed entries should use:

```md
## mem-YYYY-MM-DD-NNN - Short Title

Type: decision
Scope: template
Status: active
Confidence: high
Tags: ...
Evidence:
- ...

Decision:
...

Rationale:
...

Consequences:
- ...
```

---

## mem-2026-05-12-006 - Repo memory is the source of truth

Type: decision
Scope: template
Status: active
Confidence: high
Tags: memory, runner, retrieval
Evidence:
- harness/memory/README.md
- scripts/lint_memory.py
- harness/docs/harness-dream.md

Decision:
Harness durable memory remains file-based and Git-reviewed. A future vector store or embedding index may be added only as a retrieval cache, not as the authoritative memory store.

Rationale:
Repo-level memory is readable, auditable, reviewable, branchable, and compatible with Codex, Claude Code, OpenCode, and future SDK runners. It also lets hooks and review rules enforce memory quality without depending on a service.

Consequences:
- `harness/memory/index.yaml` is the lightweight retrieval index.
- Runner artifacts and dream reports can propose candidates but must not directly publish durable memory.
- Embeddings, if added later, must be rebuilt from committed memory and trace artifacts.

---

## mem-2026-05-12-007 - Target harness.yaml must be trackable

Type: decision
Scope: template
Status: active
Confidence: high
Tags: bootstrap, gitignore, template-runtime, harness-yaml
Evidence:
- .gitignore
- scripts/harness_runtime.py
- harness/docs/template-runtime.md

Decision:
Template repositories use ignored `harness.local.yaml` for local self-maintenance. Initialized target projects generate a real `harness.yaml`, and that file must remain trackable and committable.

Rationale:
`harness.yaml` is the target project's canonical validation, architecture, hook, and upgrade configuration. If the template ignores it, downstream projects can appear configured locally while silently failing to commit the actual harness contract.

Consequences:
- `.gitignore` must not include `harness.yaml`.
- Harness scripts read `harness.local.yaml` before `harness.yaml` so template maintainers can keep local self-bootstrap config out of Git.
- Repo-role auto-detection treats an existing `harness.yaml` as `target` unless an explicit local or environment override says otherwise.

---

## mem-2026-05-15-001 - Template version is canonicalized in harness/VERSION

Type: decision
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: version, upgrade, template, harness-yaml
Evidence:
- harness/VERSION
- scripts/lint_template_version.py
- scripts/upgrade_harness.py
- README.md

Decision:
The Harness template version is a semver value stored in `harness/VERSION`. Template examples and `harness.example.yaml` must carry the same version, and target projects must record the accepted version in `harness.yaml`.

Rationale:
Old date-like template labels were useful as notes but weak as an upgrade contract. A single semver source lets `harness-upgrade` compare source and target before applying files, lets hooks catch stale metadata, and gives maintainers a clear place to document major-version changes.

Consequences:
- `harness-upgrade` reports target/source version status before file actions.
- `scripts/lint_template_version.py` guards version metadata in templates and target projects.
- README Version History must be updated for major template changes.
- Target `harness.yaml` version changes stay MANUAL so project owners consciously accept the upgrade.

---

## mem-2026-05-18-001 - Agent runtime is local handoff state, not durable memory

Type: decision
Scope: template
Status: active
Confidence: high
Tags: agent-runtime, handoff, eval, worker, local
Evidence:
- scripts/agent_runtime.py
- harness/docs/agent-runtime-contract.md
- harness/docs/eval-governance.md
- templates/agent-task-template.yaml

Decision:
Harness uses one local Agent Runtime Contract for standalone development and future worker workspace execution. Runtime files live under ignored `harness/.local/agent-runtime/` and record state, events, validation, runtime eval, blocker, result, handoff, memory candidates, and eval candidates.

Rationale:
A long-running PM agent and a local developer need the same handoff surface: what task is running, what changed, what validation happened, what is blocked, and how to resume. Keeping this state local avoids distributing transient worker traces through the template or treating unreviewed candidates as trusted memory.

Consequences:
- `scripts/agent_runtime.py` is the common interface for start/current/resume/event/validation/eval/block/complete/export-trace.
- Runtime memory candidates must go through `memory-distill` or a normal Harness task before entering durable memory.
- Runtime eval candidates must go through owner review before entering `harness/evals/`.
- `harness-upgrade` syncs the runtime script, docs, templates, and eval governance skeleton, but never syncs `harness/.local/agent-runtime/**`.

---

## mem-2026-05-18-002 - Template quality baseline stays local while target baseline is shared

Type: decision
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: baseline, quality, template-runtime, gitignore
Evidence:
- scripts/lint_quality.py
- scripts/lint_template_clean.py
- .gitignore
- harness/docs/template-runtime.md

Decision:
Template repositories must not commit `harness/baseline.json`. When maintainers run `lint_quality.py --baseline-init` in a template repository, the default baseline path is `harness/.local/baseline.json`. Target projects may commit `harness/baseline.json` when they intentionally use `quality.baseline_mode: "baseline"` or `"drift_gate"`.

Rationale:
The baseline file is a shared migration contract for a target project, but a template repository baseline is only a local snapshot of template maintenance state. If the template ignores `harness/baseline.json`, downstream projects cannot commit their shared baseline cleanly. If the template commits it, downstream projects inherit irrelevant local debt.

Consequences:
- `.gitignore` must not ignore `harness/baseline.json`.
- `scripts/lint_template_clean.py` blocks `harness/baseline.json` only when the repository role is `template`.
- Target projects keep using `harness/baseline.json` as the default shared baseline.
- Template maintainers can override with `--baseline-file`, but normal local baselines should stay under `harness/.local/`.

---

## mem-2026-06-08-005 - Template README is not the target project README

Type: decision
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: upgrade, readme, downstream, project-owned-docs
Evidence:
- scripts/upgrade_harness.py
- .agent-harness/skills/src/harness-upgrade/SKILL.md
- harness/docs/harness-upgrade.md

Decision:
The root `README.md` in a Harness template repository documents the template itself. Target projects must keep their own README based on their product, repository structure, build/deploy commands, ownership, and actual Harness adoption state.

Rationale:
Copying or manually merging the template README into a downstream project can replace useful project onboarding with generic template documentation. Unlike framework-owned scripts, skills, local AGENTS maps, and templates, a project README is a product/repository entry point.

Consequences:
- `harness-upgrade` does not compare, add, update, or list root `README.md` as a MANUAL merge item.
- Project maintainers may update their README after an upgrade, but only with project-specific commands and workflow that are true for that repository.
- Framework-owned nested README files, such as `.review/rules/README.md` or `harness/dream/README.md`, remain normal framework upgrade artifacts.
