# Failure Patterns

Repeated failure modes and prevention playbooks. Use this file for patterns that should influence future execution, reviews, or hardening.

## Format

Indexed entries should use:

```md
## mem-YYYY-MM-DD-NNN - Short Title

Type: failure
Scope: template
Status: active
Confidence: high
Tags: ...
Evidence:
- ...

Pattern:
...

Impact:
...

Prevention:
- ...

Hardening:
- ...
```

---

## mem-2026-05-12-005 - Unindexed memory becomes context noise

Type: failure
Scope: template
Status: active
Confidence: high
Tags: memory, index, drift
Evidence:
- harness/memory/README.md
- scripts/lint_memory.py

Pattern:
Memory entries are appended as plain stories without lifecycle, scope, status, or evidence. Over time, agents cannot tell which entries are current, which are project-specific, and which are already hardened.

Impact:
- Agents load too much context.
- Old workarounds conflict with newer rules.
- Dream reports produce recommendations that are not closed.
- Template memory and target-project memory become hard to separate.

Prevention:
- Index durable entries in `harness/memory/index.yaml`.
- Add `Type`, `Scope`, `Status`, `Confidence`, `Tags`, and `Evidence` to durable memory blocks.
- Keep candidate memory in dream reports or local runner artifacts until reviewed.
- Run `python scripts/lint_memory.py` before submitting memory changes.

Hardening:
- `scripts/lint_memory.py` validates index and Markdown entry consistency.
- `memory-distill` must update `index.yaml` and archive originals when rewriting memory.

## mem-2026-06-08-001 - Inline empty YAML values must not become validation commands

Type: failure
Scope: template
Status: active
Confidence: high
Tags: yaml, parser, agent-runtime, validation, downstream
Evidence:
- scripts/harness_runtime.py
- scripts/test_harness_runtime_yaml.py

Pattern:
Dependency-free fallback YAML parsers that do not understand inline `[]` or `{}` treat `required_validation: []` as a scalar string. `agent_runtime.py check` then interprets that value as required validation commands such as `[` and `]`, producing false missing-validation failures even when the run has real validation evidence.

Impact:
- Plan/research/review runs with intentionally empty `required_validation` report misleading validation warnings or failures.
- Downstream projects without PyYAML see inconsistent runtime behavior compared with environments that have PyYAML installed.
- Agents may add fake validation records to satisfy parser artifacts instead of fixing the parser.

Prevention:
- Fallback YAML scalar parsing must map unquoted `[]` to an empty list and unquoted `{}` to an empty dict.
- Quoted `"[]"` and `"{}"` must remain strings.
- Add parser regressions that force the fallback path instead of relying on local PyYAML availability.

Hardening:
- `scripts/harness_runtime.py` and copied fallback parsers now parse inline empty list/object values.
- `scripts/test_harness_runtime_yaml.py` covers `required_validation: []` through the no-PyYAML fallback path.

## mem-2026-06-08-002 - Committed harness.yaml must not store developer-local paths

Type: failure
Scope: template
Status: active
Confidence: high
Tags: harness-yaml, upgrade, portability, project-init, downstream
Evidence:
- scripts/lint_harness_config.py
- .agent-harness/skills/src/project-init/SKILL.md
- .agent-harness/skills/src/harness-creator/SKILL.md

Pattern:
Project initialization or manual onboarding can write the developer machine path, WSL path, target project path, or `"."` into committed `harness.yaml`, especially under `harness_framework.source`. Downstream `harness-upgrade` then reads the wrong source and cannot update from the reusable template repository on another machine.

Impact:
- New developers cannot run upgrade or validation reliably after cloning.
- Target projects may point their template source at themselves, producing incorrect upgrade plans.
- Local setup details leak into Git-tracked project configuration.

Prevention:
- Committed `harness.yaml` must keep path fields repository-relative.
- `harness_framework.source` in a target project must be that project's actual template remote Git URL with a stable branch or tag; do not substitute another project's template remote.
- Developer-local template paths belong in ignored `harness.local.yaml` or an explicit `upgrade_harness.py --source ...` override.

Hardening:
- `scripts/lint_harness_config.py` rejects developer-local absolute paths in committed Harness config.
- `project-init` and `harness-creator` instructions require remote template source metadata for target `harness.yaml`.

## mem-2026-06-08-003 - PRD review sign-off must not be split across files

Type: failure
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: prd-review, docs, signoff, gate, downstream
Evidence:
- scripts/lint_prd_review_signoff.py
- .agent-harness/skills/src/prd-review/SKILL.md
- doc/features/templates/outputs/product-spec-review-template.md

Pattern:
PRD review can produce gate or phase closure drafts and then place owner signatures or approval ledgers in a separate root-level document such as `docs/phase-0-gates.md`. The generated `product-spec-review.md` still mentions approvals, but the actual source of truth becomes split across multiple files.

Impact:
- Downstream agents cannot tell whether `product-spec-review.md` is the approved product review or only a pointer to another approval document.
- `functional-spec.md` and design work may consume draft closure decisions as if they were Product/API sign-off.
- Owner approval, API freeze, implementation freeze, and release readiness evidence become harder to audit.

Prevention:
- Treat `product-spec-review.md` as the only PRD review sign-off ledger.
- Keep phase/gate closure, owner approval, freeze/readiness approval, and file-level approval tables in `product-spec-review.md`.
- Let `review-conclusion.md`, `functional-spec.md`, intermediates, trace, and root `docs/` reference the product-spec-review sign-off section but not host signatures.

Hardening:
- `scripts/lint_prd_review_signoff.py` rejects sign-off markers outside `doc/features/{feature}/generated|outputs/product-spec-review.md`.
- `prd-review` and doc templates now state the single-ledger rule explicitly.

## mem-2026-06-08-004 - Hook-referenced scripts must be upgrade-owned

Type: failure
Scope: template
Status: promoted_to_rule
Confidence: high
Tags: upgrade, hooks, validation, manifest, downstream
Evidence:
- scripts/upgrade_harness.py
- scripts/install_git_hooks.py
- scripts/lint_template_version.py

Pattern:
A template release can add a new lint or validation script and immediately wire it into `install_git_hooks.py` or `harness.example.yaml`, but forget to include the new script in `upgrade_harness.py` framework-owned items. Existing projects then update hooks or validation commands without receiving the script they invoke.

Impact:
- `harness-upgrade apply` appears successful while downstream hooks fail with missing script errors.
- Re-running plan can misleadingly show no actionable framework drift if the dependency was never represented in the upgrade manifest.
- Target project owners must manually debug template-owned dependency gaps that should have been copied automatically.

Prevention:
- Any script referenced by template default hooks or template validation commands must be covered by `upgrade_harness.py` framework-owned items.
- New hook/validation scripts must be tested against a target that lacks the file and must appear as `ADD`.

Hardening:
- `scripts/lint_harness_config.py` and `scripts/lint_prd_review_signoff.py` are now explicit framework-owned upgrade items.
- `scripts/lint_template_version.py` checks template hook/config script references against the upgrade manifest.
