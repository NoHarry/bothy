# Harness Upgrade Plan

- Target project:
- Template source:
- Template source ref:
- Target template version:
- Source template version:
- Version status:
- Date:
- Operator:

## Summary

| Type | Count | Meaning |
|---|---:|---|
| ADD | 0 | 目标项目缺失，可直接添加 |
| UPDATE | 0 | 框架拥有文件有差异，可备份后更新 |
| MANUAL | 0 | 项目化文件有差异，需负责人或维护者手工合并 |
| SKIP | 0 | 已一致 |

## Manual Merge Items

| Path | Required Decision | Owner | Target Output | Status |
|---|---|---|---|---|
|  |  |  |  | TODO |

## Manual Merge Checklist

- [ ] `AGENTS.md` skill inventory includes newly projected skills.
- [ ] Project `README.md`, if it documents Harness onboarding, reflects this project's real commands and workflow; do not merge the template repository README.
- [ ] `CONSTRAINTS.md` states `.agent-harness/skills/src` is the only skill edit source and projection directories are generated.
- [ ] `CLAUDE.md` remains a thin `AGENTS.md` adapter.
- [ ] `harness.yaml` records `harness_framework.template_version`, `source`, `source_ref`, and `last_upgrade` where appropriate.
- [ ] `.gitignore` excludes local harness runtime records and Python caches.
- [ ] `harness/memory/index.yaml` and memory governance rules are merged when new durable memory files or lint rules are introduced.
- [ ] `harness/memory/` or owner action board records project-specific follow-up items.

## Verification

```bash
python scripts/sync_skills.py --check
python scripts/lint_agent_entrypoints.py
python scripts/lint_template_version.py
python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace
python scripts/validate_harness_sync.py
python scripts/lint_memory.py
python scripts/validate.py --dry-run
```
