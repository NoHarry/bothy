# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Validate portable committed Harness configuration."""

import argparse
import re
from pathlib import Path

from harness_runtime import ROOT, load_yaml


REMOTE_PREFIXES = ("https://", "http://", "ssh://", "git://")
LOCAL_PATH_RE = re.compile(
    r"^(?:[A-Za-z]:[\\/]|\\\\|//|/|file://)",
    re.IGNORECASE,
)
COMMIT_RE = re.compile(r"^[0-9a-fA-F]{40}$")


def fail(message):
    print(f"[FAIL] {message}")


def ok(message):
    print(f"[OK] {message}")


def rel(root, path):
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def is_placeholder(value):
    return "{" in value and "}" in value


def is_remote_source(value):
    text = value.strip()
    return text.startswith(REMOTE_PREFIXES) or bool(
        re.match(r"^[^@\s]+@[^:\s]+:.+", text)
    )


def is_local_absolute_path(value):
    text = value.strip()
    return bool(LOCAL_PATH_RE.match(text))


def iter_config_paths(root):
    paths = []
    for name in ("harness.yaml", "harness.example.yaml"):
        path = root / name
        if path.exists():
            paths.append(path)
    examples_root = root / "harness" / "docs" / "examples"
    if examples_root.exists():
        paths.extend(sorted(examples_root.rglob("harness.yaml")))
    return paths


def walk_strings(value, path=""):
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{path}.{key}" if path else str(key)
            yield from walk_strings(child, child_path)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from walk_strings(child, f"{path}[{index}]")
    elif isinstance(value, str):
        yield path, value


def validate_path_values(root, config_path, data):
    errors = []
    for field_path, value in walk_strings(data):
        if is_placeholder(value):
            continue
        if is_local_absolute_path(value):
            errors.append(
                f"{rel(root, config_path)}:{field_path} contains developer-local absolute path '{value}'"
            )
    return errors


def validate_framework_source(root, config_path, data):
    framework = data.get("harness_framework") if isinstance(data, dict) else {}
    if not isinstance(framework, dict):
        return []

    source = str(framework.get("source") or "").strip()
    source_ref = str(framework.get("source_ref") or "").strip()
    source_commit = framework.get("source_commit")
    if config_path.name != "harness.yaml" or config_path.parent != root:
        return []

    errors = []
    if not source:
        errors.append("harness.yaml:harness_framework.source is required")
    elif not is_remote_source(source):
        errors.append(
            "harness.yaml:harness_framework.source must be this project's actual template remote Git URL; "
            "put local template paths in ignored harness.local.yaml or pass --source explicitly"
        )
    if source_ref.lower() == "local":
        errors.append(
            "harness.yaml:harness_framework.source_ref must not be 'local' in committed target config"
        )
    if source_commit is not None:
        commit_text = str(source_commit).strip()
        if commit_text and commit_text.lower() not in {"none", "null"} and not COMMIT_RE.fullmatch(commit_text):
            errors.append(
                "harness.yaml:harness_framework.source_commit must be empty/null or a full 40-character git commit SHA"
            )
    return errors


def main():
    parser = argparse.ArgumentParser(
        description="Validate portable committed Harness configuration."
    )
    _ = parser.add_argument(
        "--root",
        default=str(ROOT),
        help="Repository root to check. Defaults to the current Harness repository.",
    )
    args = parser.parse_args()
    root = Path(args.root).resolve()

    errors = []
    for path in iter_config_paths(root):
        data = load_yaml(path)
        if not isinstance(data, dict):
            errors.append(f"{rel(root, path)} must contain a YAML object")
            continue
        errors.extend(validate_path_values(root, path, data))
        errors.extend(validate_framework_source(root, path, data))

    if errors:
        for message in errors:
            fail(message)
        return 1

    ok("Committed Harness config is portable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
