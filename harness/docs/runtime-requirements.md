# Runtime Requirements

Harness scripts are intentionally lightweight, but the runtime still has a small supported baseline.

## Python

- Supported baseline: Python 3.9 or newer.
- Recommended: use the same Python interpreter for hooks, `workspace_doctor`, `agent_runtime`, and `validate.py`.
- `workspace_doctor.py` reports the active Python version so a cloned project can diagnose local drift before running a task.

## Python Packages

Install the framework requirements when a project uses YAML features beyond simple mappings, lists, and scalar values:

```bash
python -m pip install -r harness/requirements.txt
```

Current dependency:

- `PyYAML>=6.0`

Harness has a dependency-free YAML fallback for simple configuration files. The fallback is deliberately narrow. If PyYAML is missing and a config uses anchors, aliases, merge keys, or block scalars, `harness_runtime.load_yaml` fails with an actionable error instead of silently misreading the file.

## Validation

Run these checks after changing runtime dependencies or YAML loading behavior:

```bash
python -m unittest discover -s scripts -p "test_*.py"
python scripts/workspace_doctor.py --run-checks
python scripts/validate.py --dry-run
```
