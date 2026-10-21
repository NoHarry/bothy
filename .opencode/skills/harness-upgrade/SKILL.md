---
name: harness-upgrade
description: Upgrade an existing project that already imported the Harness framework to a newer template/framework version. Use when the Harness template evolves, new scripts/skills/docs/hooks are added, an already-onboarded project needs to absorb new framework capabilities, or the user says "更新 harness", "同步模板新增能力", "升级 harness 框架", "已导入架构的项目更新", "harness upgrade", or asks how to update old Harness projects safely.
---

# Harness Upgrade

Use this skill to update Harness infrastructure in an already-onboarded project. It is not for feature development and must not overwrite project-specific business context by default.

## Workflow

1. Confirm the target project is a git workspace and has `AGENTS.md`, `CONSTRAINTS.md`, `harness/`, `scripts/`, and `harness.yaml` or `harness.example.yaml`.

2. Locate the latest template source:
   - Prefer the user-provided path.
   - Otherwise use `harness_framework.source` if present in `harness.yaml`. In committed target config this should be that project's actual template remote Git URL. Local template paths are only for explicit `--source` overrides or ignored `harness.local.yaml`.
   - If neither exists, ask for the template path or repository.

3. Run a plan first:

   ```bash
   python {template}/scripts/upgrade_harness.py --source {template} --target {project} --mode plan --write-report
   ```

   If `--source` is omitted, the script reads `harness_framework.source` from the target project.
   Remote sources are cloned to a temporary directory and removed after the run by default.
   The plan must show `target`, `source`, and `status` for the template version check. The source version comes from `{template}/harness/VERSION`; the target version comes from target `harness.yaml` `harness_framework.template_version`.

4. Review the plan:
   - `status=upgrade-available`, `target-version-legacy`, or `target-version-missing` means the target should absorb the newer template after review.
   - `status=same-version` means apply only if repairing framework drift.
   - `status=target-newer-than-source` means stop and verify the selected template source/ref.
   - `ADD` and `UPDATE` are framework-owned files that can be applied.
   - `MANUAL` items are protected project files such as `AGENTS.md`, `CONSTRAINTS.md`, `BLACKBOARD.md`, `WORKFLOW.md`, `harness.yaml`, `doc/`, `docs/`, `harness/memory/`, and `harness/trace/`.
   - Root `README.md` is project-owned and is not compared to the template README; update it only from the target project's actual onboarding and product state.
   - Do not apply manual items automatically; convert them to owner actions or deliberate project edits.

5. Apply safe updates:

   ```bash
   python {template}/scripts/upgrade_harness.py --source {template} --target {project} --mode apply --write-report
   ```

6. Re-run the plan and confirm framework-owned files are fully converged:

   ```bash
   python {template}/scripts/upgrade_harness.py --source {template} --target {project} --mode plan
   ```

   `ADD` and `UPDATE` must be `0` before the upgrade is considered mechanically complete. Remaining `MANUAL` items are expected, but they are not optional; inspect and merge the relevant project-facing updates deliberately.

7. Merge protected project files deliberately. At minimum, check whether the target project now needs these updates:
   - `AGENTS.md`: skill inventory, project map, and workflow entries for `harness-upgrade`, `harness-dream`, `skills-sync`, `workspace-doctor`, and any newly projected skills.
   - `.agent-harness/skills/registry.project.yaml`: project-only skill extensions that have no template equivalent. If a legacy project skill duplicates a template skill, use the template skill plus project overlay/docs instead of keeping a second skill.
   - Layered `AGENTS.md`: framework-owned local maps such as `harness/AGENTS.md`, `harness/**/AGENTS.md`, `.agent-harness/AGENTS.md`, `.agent-harness/skills/AGENTS.md`, `scripts/AGENTS.md`, `templates/AGENTS.md`, `.githooks/AGENTS.md`, `.review/AGENTS.md`, and `doc/AGENTS.md`. These may be imported by apply mode; project root `AGENTS.md` remains MANUAL.
   - Project `README.md`: do not merge the template repository README. If the target project keeps Harness onboarding in README, update only the project-specific commands and workflow that are true for that repo.
   - `CONSTRAINTS.md`: rules that `.agent-harness/skills/src` is the only skill edit source, template skills use `registry.yaml`, project-only skills use `registry.project.yaml`, `.agents/.claude/.opencode` are generated projections, `CLAUDE.md` is only an `AGENTS.md` adapter, `harness-dream` is report-only, and runtime candidates are not durable assets.
   - `WORKFLOW.md`: task type defaults, validation profiles, workspace hooks, and human gates. If the file already exists, merge manually; do not overwrite.
   - `harness.yaml`: `harness_framework.template_version/source/source_ref/last_upgrade`, `agent_runtime`, `evals`, validation warnings, and owner actions for disabled build/test gates.
   - `.gitignore`: local runtime records such as `harness/.local/`, `harness/upgrade-backups/`, `*.harness-backup-*`, `harness.local.yaml`, Python cache files, and other generated artifacts.
   - `harness/memory/` or owner action board: process lessons, `index.yaml` memory governance entries, and project-specific follow-up items discovered by the upgrade.
   - `harness/evals/`: only reviewed repo eval metadata; do not copy runtime eval candidates directly.

   If new skills were added but `AGENTS.md` or project-owned onboarding docs still do not mention the relevant project workflow, the upgrade is incomplete even when `ADD` and `UPDATE` are `0`.

8. Run post-upgrade checks from the target project:

   ```bash
   python scripts/sync_skills.py --check
   python scripts/agent_runtime.py status --json
   python scripts/lint_agent_entrypoints.py
   python scripts/lint_template_version.py
   python scripts/lint_harness_config.py
   python scripts/lint_prd_review_signoff.py
   python scripts/validate_workflow.py
   python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
   python scripts/validate_harness_sync.py
   python scripts/lint_memory.py
   python scripts/validate.py --dry-run
   python scripts/lint_deps.py
   python scripts/lint_quality.py
   ```

   If `validate.py --dry-run` reports an unknown but valid top-level config field, update the template schema or explicitly move the field before submitting. Do not leave warning-only drift unexplained.

9. If new framework capabilities create project-specific decisions, write them to `hardening_debt` or an owner action board. Do not guess architecture, branch policy, CI policy, or domain constraints.

10. Submit through `commit-review`.

## Safety Rules

- Never overwrite root `AGENTS.md`, `CONSTRAINTS.md`, `README.md`, `BLACKBOARD.md`, `WORKFLOW.md`, `harness.yaml`, target business `doc/` content, `docs/`, `harness/memory/` content, or `harness/trace/` content automatically. The root `README.md` is reference-only and is not part of upgrade diffing. Framework-owned local `AGENTS.md` maps are the exception and may be imported by `harness-upgrade`.
- Existing target files changed by apply mode are backed up under `harness/upgrade-backups/{timestamp}/`.
- Remote template clones are temporary and are deleted after plan/apply unless `--keep-source` is passed.
- No obsolete file deletion is performed by default.
- If hooksPath conflicts with an existing project hook chain, record OWNER-ACTION instead of forcing replacement.
- When running inside a template repository, upgrade reports are written to ignored `harness/.local/upgrade-reports/`; target projects still use `harness/trace/`.
- `harness/.local/agent-runtime/**` is runtime state and must not be committed or copied as an upgrade artifact.
- A successful apply is not enough: protected project files must be reviewed for manual merge impact before declaring the upgrade complete.
- Do not declare an upgrade complete until target `harness.yaml` records the accepted `harness_framework.template_version`.
