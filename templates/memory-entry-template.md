# Memory Entry Template

Use this block for durable entries in `harness/memory/*.md`. Also add the same id to `harness/memory/index.yaml`.

```md
## mem-YYYY-MM-DD-NNN - Short Title

Type: procedural
Scope: template
Status: active
Confidence: high
Tags: tag-one, tag-two
Evidence:
- scripts/example.py
- trace: harness/trace/YYYY-MM-DD-task.md

Lesson:
What should future agents remember?

Use when:
- When this memory should be loaded or applied.

Do not:
- What this memory does not authorize.

Hardening:
- Script, hook, review rule, or config gate if one exists.
```

Allowed values:

- `Type`: `semantic`, `procedural`, `failure`, `reflective`, `policy`, `decision`
- `Scope`: `template`, `project`, `local`
- `Status`: `candidate`, `active`, `superseded`, `deprecated`, `promoted_to_rule`
- `Confidence`: `low`, `medium`, `high`
