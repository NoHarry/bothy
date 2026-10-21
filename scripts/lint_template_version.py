# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Validate Harness template version metadata consistency."""

import re
from pathlib import Path

from harness_runtime import ROOT, is_template_repo, load_yaml


VERSION_RE = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
SCRIPT_COMMAND_RE = re.compile(r"\bpython\s+(scripts/[A-Za-z0-9_./-]+\.py)\b")
VERSION_PATH = ROOT / "harness" / "VERSION"
README_PATH = ROOT / "README.md"


def fail(message):
    print(f"[FAIL] {message}")


def ok(message):
    print(f"[OK] {message}")


def read_version():
    if not VERSION_PATH.exists():
        fail("Missing harness/VERSION")
        return ""
    version = VERSION_PATH.read_text(encoding="utf-8").strip()
    if not VERSION_RE.fullmatch(version):
        fail("harness/VERSION must be semver, for example 1.0.0")
        return ""
    return version


def framework_version(path):
    data = load_yaml(path)
    framework = data.get("harness_framework") if isinstance(data, dict) else {}
    if not isinstance(framework, dict):
        return ""
    return str(framework.get("template_version") or "").strip()


def check_yaml_version(path, expected):
    found = framework_version(path)
    if not found:
        return [f"{path.relative_to(ROOT).as_posix()} missing harness_framework.template_version"]
    if found != expected:
        return [
            f"{path.relative_to(ROOT).as_posix()} template_version is {found}, expected {expected}"
        ]
    return []


def check_readme(expected):
    if not README_PATH.exists():
        return ["README.md is missing"]
    text = README_PATH.read_text(encoding="utf-8", errors="ignore")
    errors = []
    major = expected.split(".", 1)[0]
    if "## Version History" not in text:
        errors.append("README.md missing ## Version History")
    if f"### {major}.x" not in text:
        errors.append(f"README.md missing major version section ### {major}.x")
    if expected not in text:
        errors.append(f"README.md missing current version {expected}")
    return errors


def iter_strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for nested in value.values():
            yield from iter_strings(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from iter_strings(nested)


def referenced_scripts_in_text(text):
    return set(SCRIPT_COMMAND_RE.findall(text))


def manifest_covers(path_text, manifest_items):
    path = path_text.strip("/")
    for item in manifest_items:
        normalized = str(item).strip("/")
        if path == normalized or path.startswith(normalized + "/"):
            return True
    return False


def check_upgrade_manifest_script_dependencies():
    try:
        import install_git_hooks
        import upgrade_harness
    except ImportError as error:
        return [f"Unable to inspect upgrade manifest dependencies: {error}"]

    referenced = set()
    for commands in install_git_hooks.DEFAULT_COMMANDS.values():
        for command in commands:
            referenced.update(referenced_scripts_in_text(command))

    config_paths = [ROOT / "harness.example.yaml"]
    examples = ROOT / "harness" / "docs" / "examples"
    if examples.exists():
        config_paths.extend(sorted(examples.rglob("harness.yaml")))
    for path in config_paths:
        if not path.exists():
            continue
        for value in iter_strings(load_yaml(path)):
            referenced.update(referenced_scripts_in_text(value))

    manifest_items = [
        *getattr(upgrade_harness, "FRAMEWORK_ITEMS", []),
        *getattr(upgrade_harness, "ADD_ONLY_ITEMS", []),
    ]
    errors = []
    for rel in sorted(referenced):
        if not (ROOT / rel).exists():
            errors.append(f"{rel} is referenced by hooks/config but the file is missing")
        elif not manifest_covers(rel, manifest_items):
            errors.append(
                f"{rel} is referenced by hooks/config but missing from upgrade_harness framework items"
            )
    return errors


def main():
    version = read_version()
    if not version:
        return 1

    errors = []
    template_repo = is_template_repo(ROOT)
    if (ROOT / "harness.example.yaml").exists():
        errors.extend(check_yaml_version(ROOT / "harness.example.yaml", version))
    if (ROOT / "harness.yaml").exists():
        errors.extend(check_yaml_version(ROOT / "harness.yaml", version))
    if template_repo:
        for path in sorted((ROOT / "harness" / "docs" / "examples").rglob("harness.yaml")):
            errors.extend(check_yaml_version(path, version))
        errors.extend(check_readme(version))
        errors.extend(check_upgrade_manifest_script_dependencies())

    if errors:
        for message in errors:
            fail(message)
        return 1

    ok(f"Template version metadata is valid ({version}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
