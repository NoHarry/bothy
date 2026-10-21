---
name: harness-dream
description: Run a read-only offline Harness review that summarizes trace, memory, validation, and hardening signals into a dream report. Use when the project needs periodic reflection, repeated failure analysis, memory cleanup suggestions, or owner action discovery.
license: MIT
metadata:
  author: noharry
  team: infra
  version: "0.1.0"
---

# Harness Dream

Use this skill to run an offline maintenance pass over Harness records. It is a report-only workflow in this version.

## Scope

Inputs:

- `harness/trace/`
- `harness/memory/`
- `harness/.local/agent-runtime/`
- `BLACKBOARD.md`
- `hardening_debt` in `harness.yaml` or `harness.example.yaml`
- validation, build, test, skip, failure, and owner-action signals found in recent records

Outputs:

- Memory cleanup suggestions
- Hardening candidates
- Owner actions
- Missing validation evidence
- Repeated failure signals
- Structured findings with evidence, confidence, and `recommended_next_skill`
- Candidate memory suggestions that still require a separate review flow
- Runtime eval candidate references that still require owner review before promotion to `harness/evals/`

## Safety Rules

- Do not modify source code.
- Do not modify memory files.
- Do not modify evals, prompts, hooks, policies, or validation rules.
- Do not commit dream reports from a template repository.
- Convert recommendations into owner actions or normal Harness tasks before applying changes.
- Do not promote candidate memory directly from the dream report into durable memory.
- Do not promote runtime eval candidates directly into `harness/evals/`.

## Workflow

1. Confirm this is a Harness workspace.
2. Run:

   ```bash
   python scripts/dream_runner.py --limit 20 --write-report
   ```

3. Read the report:
   - Template repository reports are written to `harness/.local/dream/reports/`.
   - Target project reports are written to `harness/dream/reports/`.

4. Classify findings:
   - memory noise -> propose `memory-distill`
   - repeated failure -> propose test, gate, or hardening debt
   - missing validation -> ask owner or run the relevant validation
   - unclear project policy -> owner action
   - candidate memory -> keep as candidate until `memory-distill` or a normal Harness task approves it
   - eval candidate -> keep as candidate until an owner-reviewed Harness task promotes it to `harness/evals/`

5. If changes are needed, start a separate Harness task with the appropriate skill. Do not apply changes inside the dream pass.

## Trigger Guidance

Run this skill when:

- recent trace count grows beyond 10
- validation fails repeatedly
- a large manual change lacks trace or validation evidence
- memory files become noisy
- a template upgrade lands
- the user asks for reflection, dream, offline review, or repeated-failure analysis

## Completion Criteria

- A dream report exists at the correct runtime path.
- The report was treated as advice, not as an automatic change set.
- Findings include evidence, confidence, and recommended next skill when actionable.
- Any follow-up is represented as an owner action or separate task.
