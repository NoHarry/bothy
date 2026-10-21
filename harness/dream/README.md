# Harness Dream Reports

This directory documents the target-project dream report location.

Target projects write reviewed `harness-dream` reports under `harness/dream/reports/`. The report directory is created at runtime by `scripts/dream_runner.py` and should contain generated reports only.

Template repositories do not commit dream reports. Template self-maintenance writes reports under ignored `harness/.local/dream/reports/`, and `scripts/lint_template_clean.py` blocks committing `harness/dream/reports/`.
