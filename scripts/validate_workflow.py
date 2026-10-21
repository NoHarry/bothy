# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Validate the repository-owned WORKFLOW.md execution contract."""

import argparse
import importlib
import re
from pathlib import Path

from harness_runtime import ROOT, is_template_repo, load_yaml, parse_yaml


SUPPORTED_VERSIONS = {1}
AUTONOMY_LEVELS = {"L0", "L1", "L2", "L3", "L4"}
TASK_TYPES = {
    "bugfix",
    "feature",
    "test",
    "refactor",
    "migration",
    "harness-upgrade",
    "external-validation",
    "docs",
}
VALIDATION_STAGES = {
    "preflight",
    "before_run",
    "post_agent",
    "post_codex",
    "after_run",
    "before_publish",
}
WORKSPACE_HOOK_STAGES = {"after_create", "before_run", "after_run", "before_cleanup"}
HUMAN_GATE_KEYS = {"protected_paths", "require_human_for"}
EXTERNAL_VALIDATION_MODES = {"advisory", "required", "manual"}
DANGEROUS_KEYS = {
    "api_key",
    "token",
    "password",
    "secret",
    "private_key",
    "runtime_root",
    "api_port",
    "codex_key",
    "ssh_key",
    "git_token",
    "max_global_concurrency",
    "disable_policy_guard",
    "skip_platform_review",
    "push_main",
    "app_server_command",
    "sandbox_baseline",
}
SAFE_COMMAND_RE = re.compile(
    r"^(python|python3|py)\s+scripts/[A-Za-z0-9_./-]+\.py(?:\s+[-A-Za-z0-9_./:=,@{}\"' ]*)?$"
)


def parse_args():
    parser = argparse.ArgumentParser(description="Validate WORKFLOW.md.")
    _ = parser.add_argument("--file", default="WORKFLOW.md", help="Workflow markdown file to validate.")
    _ = parser.add_argument("--require", action="store_true", help="Fail if the workflow file is missing.")
    return parser.parse_args()


def fail(errors, field_path, message):
    errors.append(f"{field_path}: {message}")


def warn(warnings, field_path, message):
    warnings.append(f"{field_path}: {message}")


def load_front_matter(path):
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    if not lines or lines[0].strip() != "---":
        raise ValueError("WORKFLOW.md must start with YAML front matter delimited by ---")
    end = None
    for index, line in enumerate(lines[1:], start=1):
        if line.strip() == "---":
            end = index
            break
    if end is None:
        raise ValueError("WORKFLOW.md front matter is missing the closing --- delimiter")
    yaml_text = "\n".join(lines[1:end])
    body = "\n".join(lines[end + 1 :]).strip()
    try:
        module = importlib.import_module("yaml")
        loader = getattr(module, "safe_load", None)
    except ImportError:
        loader = None
    data = loader(yaml_text) if callable(loader) else parse_yaml(yaml_text)
    if not isinstance(data, dict):
        raise ValueError("WORKFLOW.md front matter must be a mapping")
    return data, body


def skill_names():
    names = set()
    registry_root = ROOT / ".agent-harness" / "skills"
    for registry_path in (registry_root / "registry.yaml", registry_root / "registry.project.yaml"):
        registry = load_yaml(registry_path) if registry_path.exists() else {}
        skills = registry.get("skills") if isinstance(registry, dict) else []
        if isinstance(skills, list):
            for item in skills:
                if isinstance(item, dict) and item.get("name"):
                    names.add(str(item.get("name")))
    for base in (".agents/skills", ".claude/skills", ".opencode/skills"):
        root = ROOT / base
        if root.exists():
            names.update(path.parent.name for path in root.glob("*/SKILL.md"))
    return names


def walk_keys(value, prefix=""):
    if isinstance(value, dict):
        for key, child in value.items():
            key_text = str(key)
            path = f"{prefix}.{key_text}" if prefix else key_text
            yield path, key_text, child
            yield from walk_keys(child, path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            path = f"{prefix}[{index}]"
            yield from walk_keys(child, path)


def validate_dangerous_fields(data, errors):
    for path, key, value in walk_keys(data):
        normalized = key.lower().replace("-", "_")
        if normalized in DANGEROUS_KEYS:
            fail(errors, path, "dangerous platform or credential field is not allowed in WORKFLOW.md")
        if isinstance(value, str):
            lowered = value.lower()
            for forbidden in ("skip_platform_review", "disable_policy_guard", "push_main"):
                if forbidden in lowered:
                    fail(errors, path, f"dangerous value contains {forbidden}")


def validate_task_types(data, skills, errors, warnings):
    task_types = data.get("task_types")
    profiles = data.get("validation_profiles") if isinstance(data.get("validation_profiles"), dict) else {}
    if task_types is None:
        warn(warnings, "task_types", "no task type defaults are declared")
        return
    if not isinstance(task_types, dict):
        fail(errors, "task_types", "must be a mapping")
        return
    for name, config in task_types.items():
        path = f"task_types.{name}"
        if str(name) not in TASK_TYPES:
            warn(warnings, path, "non-standard task type; validator will allow it")
        if not isinstance(config, dict):
            fail(errors, path, "must be a mapping")
            continue
        skill = str(config.get("default_skill") or "").strip()
        if not skill:
            fail(errors, f"{path}.default_skill", "is required")
        elif skill not in skills:
            fail(errors, f"{path}.default_skill", f"unknown skill '{skill}'")
        autonomy = str(config.get("autonomy_level") or "").strip()
        if autonomy and autonomy not in AUTONOMY_LEVELS:
            fail(errors, f"{path}.autonomy_level", "must be one of L0, L1, L2, L3, L4")
        profile = str(config.get("validation_profile") or "").strip()
        if profile and profile not in profiles:
            fail(errors, f"{path}.validation_profile", f"unknown validation profile '{profile}'")
        if "require_plan_review" in config and not isinstance(config.get("require_plan_review"), bool):
            fail(errors, f"{path}.require_plan_review", "must be boolean")


def validate_command_list(value, field_path, errors):
    if not isinstance(value, list) or not value:
        fail(errors, field_path, "must be a non-empty list of commands")
        return
    for index, command in enumerate(value):
        command_path = f"{field_path}[{index}]"
        if not isinstance(command, str) or not command.strip():
            fail(errors, command_path, "command must be a non-empty string")
            continue
        if any(marker in command for marker in ("&&", "||", ";", "|", "`", "$(")):
            fail(errors, command_path, "compound shell operators are not allowed")


def validate_validation_profiles(data, errors, warnings):
    profiles = data.get("validation_profiles")
    if profiles is None:
        warn(warnings, "validation_profiles", "no validation profiles are declared")
        return
    if not isinstance(profiles, dict):
        fail(errors, "validation_profiles", "must be a mapping")
        return
    for profile, stages in profiles.items():
        path = f"validation_profiles.{profile}"
        if not isinstance(stages, dict):
            fail(errors, path, "must be a mapping")
            continue
        for stage, commands in stages.items():
            stage_path = f"{path}.{stage}"
            if str(stage) not in VALIDATION_STAGES:
                warn(warnings, stage_path, "non-standard validation stage; validator will allow it")
            validate_command_list(commands, stage_path, errors)


def validate_workspace_hooks(data, errors, warnings):
    hooks = data.get("workspace_hooks")
    if hooks is None:
        return
    if not isinstance(hooks, dict):
        fail(errors, "workspace_hooks", "must be a mapping")
        return
    for stage, commands in hooks.items():
        path = f"workspace_hooks.{stage}"
        if str(stage) not in WORKSPACE_HOOK_STAGES:
            warn(warnings, path, "non-standard workspace hook stage; validator will allow it")
        validate_command_list(commands, path, errors)
        if isinstance(commands, list):
            for index, command in enumerate(commands):
                if isinstance(command, str) and command.strip() and not SAFE_COMMAND_RE.fullmatch(command.strip()):
                    fail(errors, f"{path}[{index}]", "workspace hook must call a repository script through python")


def validate_human_gates(data, errors, warnings):
    gates = data.get("human_gates")
    if gates is None:
        return
    if not isinstance(gates, dict):
        fail(errors, "human_gates", "must be a mapping")
        return
    for key in gates:
        if key not in HUMAN_GATE_KEYS:
            warn(warnings, f"human_gates.{key}", "non-standard human gate key; validator will allow it")
    protected = gates.get("protected_paths")
    if protected is not None:
        if not isinstance(protected, list) or not protected:
            fail(errors, "human_gates.protected_paths", "must be a non-empty list")
        else:
            for index, pattern in enumerate(protected):
                if not isinstance(pattern, str) or not pattern.strip():
                    fail(errors, f"human_gates.protected_paths[{index}]", "path pattern must be a non-empty string")
    required = gates.get("require_human_for")
    if required is not None:
        if not isinstance(required, list) or not required:
            fail(errors, "human_gates.require_human_for", "must be a non-empty list")
        else:
            for index, item in enumerate(required):
                if not isinstance(item, str) or not item.strip():
                    fail(errors, f"human_gates.require_human_for[{index}]", "entry must be a non-empty string")


def validate_external_validation(data, errors):
    external = data.get("external_validation")
    if external is None:
        return
    if not isinstance(external, dict):
        fail(errors, "external_validation", "must be a mapping")
        return
    for name, config in external.items():
        path = f"external_validation.{name}"
        if not isinstance(config, dict):
            fail(errors, path, "must be a mapping")
            continue
        mode = str(config.get("mode") or "advisory").strip()
        if mode not in EXTERNAL_VALIDATION_MODES:
            fail(errors, f"{path}.mode", "must be advisory, required, or manual")


def validate(path, require_file):
    errors = []
    warnings = []
    if not path.exists():
        if require_file or is_template_repo(ROOT):
            fail(errors, path.name, "missing workflow contract")
        else:
            warn(warnings, path.name, "missing; fallback workflow may be used by external runners")
        return errors, warnings
    try:
        data, body = load_front_matter(path)
    except ValueError as error:
        fail(errors, path.name, str(error))
        return errors, warnings
    version = data.get("version")
    if version not in SUPPORTED_VERSIONS:
        fail(errors, "version", "must be a supported integer schema version")
    if not body:
        fail(errors, "markdown body", "workflow prompt body is required")
    validate_dangerous_fields(data, errors)
    skills = skill_names()
    validate_validation_profiles(data, errors, warnings)
    validate_task_types(data, skills, errors, warnings)
    validate_workspace_hooks(data, errors, warnings)
    validate_human_gates(data, errors, warnings)
    validate_external_validation(data, errors)
    return errors, warnings


def main():
    args = parse_args()
    path = ROOT / args.file
    errors, warnings = validate(path, args.require)
    for item in warnings:
        print(f"[WARN] {item}")
    if errors:
        for item in errors:
            print(f"[FAIL] {item}")
        return 1
    print(f"[OK] Workflow contract is valid: {path.relative_to(ROOT).as_posix()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
