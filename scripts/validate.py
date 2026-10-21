# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportUnknownMemberType=false, reportUnknownLambdaType=false, reportUnknownReturnType=false, reportArgumentType=false, reportAny=false, reportExplicitAny=false, reportDeprecated=false, reportUnusedCallResult=false, reportUnusedImport=false
"""Unified validation pipeline entry point for the Harness framework."""

import argparse
import json
import subprocess
import time as time_module
from datetime import date
from pathlib import Path

from harness_runtime import load_yaml, performance_path


ROOT = Path(__file__).resolve().parents[1]
STEP_ORDER = [
    ("build", "build"),
    ("lint-arch", "lint_arch"),
    ("test", "test"),
    ("verify", "verify"),
]
DEFAULT_SLA_SECONDS = 15.0
VALID_PERFORMANCE_RECORD_MODES = {"tracked", "local", "off"}

SCHEMA_WHITELIST = {
    "project",
    "platforms",
    "doc",
    "validation",
    "architecture",
    "quality",
    "workflow",
    "harness_framework",
    "harness_runtime",
    "agent_runtime",
    "agent_tools",
    "evals",
    "git_hooks",
    "hardening_debt",
    "ui_validation",
}


def triple(status, field_path, what, why, how):
    """Triple-message format: [STATUS] field_path\n  What: ...\n  Why: ...\n  How: ..."""
    print(f"{status} {field_path}")
    print(f"  What: {what}")
    print(f"  Why:  {why}")
    print(f"  How:  {how}")


def err(field_path, what, why, how):
    triple("[ERROR]", field_path, what, why, how)


def warn(field_path, what, why, how):
    triple("[WARN]", field_path, what, why, how)


def strip_comment(line):
    out = []
    in_single = False
    in_double = False
    for char in line:
        if char == "'" and not in_double:
            in_single = not in_single
        elif char == '"' and not in_single:
            in_double = not in_double
        elif char == "#" and not in_single and not in_double:
            break
        out.append(char)
    return "".join(out).rstrip()


def parse_scalar(raw):
    raw = raw.strip()
    if not raw:
        return ""
    if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in {"'", '"'}:
        return raw[1:-1]
    if raw == "[]":
        return []
    if raw == "{}":
        return {}
    lowered = raw.lower()
    if lowered == "true":
        return True
    if lowered == "false":
        return False
    if lowered in {"null", "none"}:
        return None
    for caster in (int, float):
        try:
            return caster(raw)
        except ValueError:
            pass
    return raw


def parse_yaml(text):
    rows = []
    for raw_line in text.splitlines():
        clean = strip_comment(raw_line)
        if not clean.strip():
            continue
        rows.append((len(clean) - len(clean.lstrip(" ")), clean.strip()))
    if not rows:
        return {}

    index = 0

    def parse(indent):
        nonlocal index
        if rows[index][1].startswith("- "):
            items = []
            while index < len(rows):
                current, content = rows[index]
                if current != indent or not content.startswith("- "):
                    break
                payload = content[2:].strip()
                index += 1
                if not payload:
                    if index < len(rows) and rows[index][0] > current:
                        items.append(parse(rows[index][0]))
                    else:
                        items.append(None)
                    continue
                key, separator, rest = payload.partition(":")
                if separator:
                    item = {
                        key.strip(): parse_scalar(rest.strip()) if rest.strip() else {}
                    }
                    if (
                        not rest.strip()
                        and index < len(rows)
                        and rows[index][0] > current
                    ):
                        item[key.strip()] = parse(rows[index][0])
                    while (
                        index < len(rows)
                        and rows[index][0] > current
                        and not rows[index][1].startswith("- ")
                    ):
                        extra = parse(rows[index][0])
                        if isinstance(extra, dict):
                            item.update(extra)
                    items.append(item)
                else:
                    items.append(parse_scalar(payload))
            return items

        data = {}
        while index < len(rows):
            current, content = rows[index]
            if current != indent or content.startswith("- "):
                break
            key, separator, rest = content.partition(":")
            if not separator:
                raise ValueError(f"Invalid mapping line: {content}")
            index += 1
            key = key.strip()
            rest = rest.strip()
            if rest:
                data[key] = parse_scalar(rest)
            elif index < len(rows) and rows[index][0] > current:
                data[key] = parse(rows[index][0])
            else:
                data[key] = {}
        return data

    data = parse(rows[0][0])
    if not isinstance(data, dict):
        raise ValueError("Top-level YAML value must be an object")
    return data


def load_config():
    json_path = ROOT / "harness.json"
    for yaml_path in (ROOT / "harness.local.yaml", ROOT / "harness.yaml"):
        if not yaml_path.exists():
            continue
        data = load_yaml(yaml_path)
        if not isinstance(data, dict):
            raise ValueError(f"{yaml_path.name} must contain an object at the top level")
        return data, yaml_path
    if json_path.exists():
        data = json.loads(json_path.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("harness.json must contain an object at the top level")
        return data, json_path
    raise FileNotFoundError("Missing harness.local.yaml, harness.yaml, and harness.json")


def parse_args():
    parser = argparse.ArgumentParser(description="Run the harness validation pipeline.")
    _ = parser.add_argument(
        "--step",
        choices=["build", "lint-arch", "lint_arch", "test", "verify"],
        help="Run a single validation step.",
    )
    _ = parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Show commands without executing them.",
    )
    return parser.parse_args()


def step_key(name):
    return name.lower().replace("-", "_")


def performance_settings(validation):
    settings = {
        "sla_seconds": DEFAULT_SLA_SECONDS,
        "record": "tracked",
    }
    raw = validation.get("performance") if isinstance(validation, dict) else {}
    if not isinstance(raw, dict):
        return settings

    if "sla_seconds" in raw:
        try:
            sla_seconds = float(raw.get("sla_seconds"))
            if sla_seconds <= 0:
                raise ValueError
            settings["sla_seconds"] = sla_seconds
        except (TypeError, ValueError):
            warn(
                "validation.performance.sla_seconds",
                "Performance SLA must be a positive number.",
                "Invalid SLA settings make validation timing warnings unreliable.",
                f"Use a positive number of seconds, for example {DEFAULT_SLA_SECONDS:g}.",
            )

    record = raw.get("record")
    if isinstance(record, bool):
        settings["record"] = "tracked" if record else "off"
    elif record is not None:
        record_mode = str(record).strip().lower()
        if record_mode in VALID_PERFORMANCE_RECORD_MODES:
            settings["record"] = record_mode
        else:
            warn(
                "validation.performance.record",
                f"Unknown performance record mode '{record}'.",
                "Only tracked, local, and off are supported.",
                "Set validation.performance.record to tracked, local, or off.",
            )
    return settings


def resolve_performance_path(record_mode):
    if record_mode == "local":
        return ROOT / "harness" / ".local" / "memory" / "performance.md"
    return performance_path(ROOT)


def load_config_or_report():
    try:
        return load_config()
    except FileNotFoundError:
        err(
            "harness.yaml / harness.json",
            "Harness configuration was not found.",
            "validate.py requires harness.yaml or harness.json at the project root.",
            "Create harness.yaml with a validation section, or generate it from harness.example.yaml during project initialization.",
        )
    except (ValueError, json.JSONDecodeError) as error:
        err(
            "harness.yaml / harness.json",
            "Harness configuration could not be parsed.",
            str(error),
            "Fix the configuration file syntax and run validate.py again.",
        )
    return None, None


def validate_schema(config):
    exit_code = 0

    for top_key in config:
        if top_key not in SCHEMA_WHITELIST:
            warn(
                top_key,
                f"Unknown top-level field '{top_key}' is not in the schema whitelist.",
                "Only known top-level fields are allowed to ensure configuration consistency.",
                f"Remove '{top_key}' from the top level or update SCHEMA_WHITELIST in validate.py if it is a valid field.",
            )

    project = config.get("project")
    if not isinstance(project, dict) or not project.get("name"):
        err(
            "project.name",
            "Missing or empty required field 'project.name'.",
            "The project name is required to identify the project in logs and reports.",
            "Set project.name in harness.yaml.",
        )
        exit_code = 1

    if not isinstance(project, dict) or not project.get("language"):
        err(
            "project.language",
            "Missing or empty required field 'project.language'.",
            "The language is required to configure language-specific validation rules.",
            "Set project.language in harness.yaml.",
        )
        exit_code = 1

    if exit_code == 1:
        return 1, "unknown", {}, {}

    project_name = project.get("name", "unknown") if isinstance(project, dict) else "unknown"

    platforms_cfg = config.get("platforms")
    if not isinstance(platforms_cfg, dict) or not platforms_cfg:
        err(
            "platforms",
            "Missing or empty required field 'platforms'.",
            "At least one platform must be defined to run per-platform steps.",
            "Define at least one platform under platforms: in harness.yaml.",
        )
        exit_code = 1
    else:
        for pname, pcfg in platforms_cfg.items():
            if not isinstance(pcfg, dict) or not pcfg.get("path"):
                err(
                    f"platforms.{pname}.path",
                    f"Platform '{pname}' is missing or has empty 'path'.",
                    "Each platform must have a 'path' field pointing to its root directory.",
                    f"Set platforms.{pname}.path in harness.yaml.",
                )
                exit_code = 1

    validation = config.get("validation")
    if not isinstance(validation, dict):
        err(
            "validation",
            "Missing or invalid required field 'validation'.",
            "The validation section is required to configure pipeline steps.",
            "Define validation as an object in harness.yaml.",
        )
        exit_code = 1
    else:
        lint_arch = validation.get("lint_arch")
        if not isinstance(lint_arch, dict) or lint_arch.get("enabled") is not True:
            err(
                "validation.lint_arch.enabled",
                "Required field 'validation.lint_arch.enabled' is not set to true.",
                "lint_arch must be enabled to verify architecture layer dependencies.",
                "Set validation.lint_arch.enabled: true in harness.yaml.",
            )
            exit_code = 1

        verify = validation.get("verify")
        if not isinstance(verify, dict) or verify.get("enabled") is not True:
            err(
                "validation.verify.enabled",
                "Required field 'validation.verify.enabled' is not set to true.",
                "verify must be enabled to run quality checks.",
                "Set validation.verify.enabled: true in harness.yaml.",
            )
            exit_code = 1

    return exit_code, project_name, validation if isinstance(validation, dict) else {}, (
        platforms_cfg if isinstance(platforms_cfg, dict) else {}
    )


def run_per_platform_step(display_name, config_name, platforms, args, config_path):
    if not platforms:
        err(
            "platforms",
            f'Validation step "{display_name}" has no platforms configured.',
            f"{config_path.name} does not define a top-level platforms mapping.",
            f"Add at least one entry under platforms: with a {config_name} command.",
        )
        return 1
    any_platform_failed = False
    for platform_name, platform_cfg in platforms.items():
        if not isinstance(platform_cfg, dict):
            continue
        command = str(platform_cfg.get(config_name, "")).strip()
        cwd = str(platform_cfg.get("path", ".")).strip() or "."
        if not command:
            print(f"[SKIP] {display_name} [{platform_name}] — no command")
            continue
        label = f"{display_name} [{platform_name}]"
        if args.dry_run:
            print(f"[DRY-RUN] {label} -> (cd {cwd}) {command}")
            continue
        result = subprocess.run(command, shell=True, cwd=str(ROOT / cwd))
        if result.returncode != 0:
            print(f"[FAIL] {label} — exit code {result.returncode}")
            any_platform_failed = True
        else:
            print(f"[PASS] {label}")
    return 1 if any_platform_failed else 0


def run_repo_step(display_name, config_name, step, args, config_path):
    command = str(step.get("command", "")).strip()
    if not command:
        err(
            f"validation.{config_name}.command",
            f'Validation step "{display_name}" has no command.',
            "Enabled repo-level pipeline steps cannot run without a command.",
            f"Set validation.{config_name}.command in {config_path.name}.",
        )
        return 1
    if args.dry_run:
        print(f"[DRY-RUN] {display_name} -> {command}")
        return 0
    result = subprocess.run(command, shell=True, cwd=ROOT)
    if result.returncode != 0:
        print(f"[FAIL] {display_name} — exit code {result.returncode}")
        return 1
    print(f"[PASS] {display_name}")
    return 0


def run_validation_steps(args, validation, platforms, config_path):
    requested = step_key(args.step) if args.step else None
    matched = False
    per_platform_steps = {"build", "test"}
    for display_name, config_name in STEP_ORDER:
        if requested and requested not in {step_key(display_name), config_name}:
            continue
        matched = True
        step = validation.get(config_name) or validation.get(display_name)
        if not isinstance(step, dict):
            err(
                f"validation.{config_name}",
                f'Validation step "{display_name}" is not configured.',
                "Each pipeline step must be an object with enabled (and command for non per-platform steps).",
                f"Add validation.{config_name} to {config_path.name} before running this step.",
            )
            return 1
        if not step.get("enabled", True):
            print(f"[SKIP] {display_name}")
            continue

        if config_name == "test" and str(step.get("command", "")).strip():
            result = run_repo_step(display_name, config_name, step, args, config_path)
            if result:
                return result
            continue

        if config_name in per_platform_steps:
            result = run_per_platform_step(display_name, config_name, platforms, args, config_path)
            if result:
                return result
            continue

        result = run_repo_step(display_name, config_name, step, args, config_path)
        if result:
            return result

    if not matched:
        err(
            "--step",
            "Requested validation step is not available.",
            "The selected name does not map to build, lint-arch, test, or verify.",
            "Use --step build, --step lint-arch, --step test, or --step verify.",
        )
        return 1
    return 0


def record_performance(validation, project_name, elapsed, exit_code):
    perf_settings = performance_settings(validation)
    sla_seconds = perf_settings["sla_seconds"]
    print(f"[TIMING] validate.py completed in {elapsed:.2f}s")
    if elapsed > sla_seconds:
        print(f"[WARN] validate.py SLA ({sla_seconds:g}s) exceeded")

    if perf_settings["record"] == "off":
        return exit_code

    perf_path = resolve_performance_path(perf_settings["record"])
    status_str = "pass" if exit_code == 0 else "fail"
    today = date.today().isoformat()
    perf_entry = (
        f"| {today} | validate.py | {project_name} | {elapsed:.2f}s | {status_str} |\n"
    )

    perf_path.parent.mkdir(parents=True, exist_ok=True)
    if perf_path.exists():
        existing = perf_path.read_text(encoding="utf-8")
        perf_path.write_text(existing + perf_entry, encoding="utf-8")
    else:
        header = "# Performance Log\n\n| Date | Script | Project | Elapsed | Status |\n|------|--------|---------|---------|--------|\n"
        perf_path.write_text(header + perf_entry, encoding="utf-8")
    return exit_code


def main():
    t0 = time_module.perf_counter()
    args = parse_args()
    config, config_path = load_config_or_report()
    if not config:
        return 1

    schema_exit, project_name, validation, platforms = validate_schema(config)
    if schema_exit:
        elapsed = time_module.perf_counter() - t0
        print(f"[TIMING] validate.py completed in {elapsed:.2f}s")
        return schema_exit

    exit_code = run_validation_steps(args, validation, platforms, config_path)
    if exit_code:
        return exit_code

    elapsed = time_module.perf_counter() - t0
    return record_performance(validation, project_name, elapsed, exit_code)


if __name__ == "__main__":
    raise SystemExit(main())
