# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportUnknownMemberType=false, reportUnknownLambdaType=false, reportUnknownReturnType=false, reportArgumentType=false, reportAny=false, reportExplicitAny=false, reportDeprecated=false, reportUnusedCallResult=false, reportUnusedImport=false
"""Pre-action validator for Harness Engineering file operations."""

import argparse
import fnmatch
import importlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ACTION_RE = re.compile(r"^(create|modify|delete)\s+file\s+(.+)$", re.IGNORECASE)
PROTECTED_NAMES = {"AGENTS.md", "CONSTRAINTS.md"}
SOURCE_SUFFIXES = {
    ".py",
    ".js",
    ".jsx",
    ".mjs",
    ".cjs",
    ".ts",
    ".tsx",
    ".java",
    ".go",
    ".kt",
    ".kts",
    ".rs",
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


def info(field_path, what, why, how):
    triple("[OK]", field_path, what, why, how)


def blocked(what, why, fix):
    print(f"[BLOCKED] {what}")
    print(f"  Why: {why}")
    print(f"  Fix: {fix}")


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
                    items.append(
                        parse(rows[index][0])
                        if index < len(rows) and rows[index][0] > current
                        else None
                    )
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
        text = yaml_path.read_text(encoding="utf-8")
        loader = None
        try:
            module = importlib.import_module("yaml")
            loader = getattr(module, "safe_load", None)
        except ImportError:
            loader = None
        data = loader(text) if callable(loader) else parse_yaml(text)
        if not isinstance(data, dict):
            raise ValueError(f"{yaml_path.name} must contain an object at the top level")
        return data
    if json_path.exists():
        data = json.loads(json_path.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("harness.json must contain an object at the top level")
        return data
    raise FileNotFoundError("Missing harness.local.yaml, harness.yaml, and harness.json")


def parse_args():
    parser = argparse.ArgumentParser(description="Validate a proposed file action.")
    _ = parser.add_argument(
        "--action",
        required=True,
        help="Example: create file workspace/apps/server/src/app.py",
    )
    _ = parser.add_argument(
        "--changes", default="", help="Optional change summary for import checks."
    )
    _ = parser.add_argument(
        "--force", action="store_true", help="Allow protected file targets."
    )
    return parser.parse_args()


def normalize_path(raw_path):
    cleaned = raw_path.strip().strip('"').strip("'")
    path = Path(cleaned)
    if path.is_absolute():
        try:
            path = path.resolve().relative_to(ROOT.resolve())
        except ValueError as error:
            raise ValueError("Path is outside the project root") from error
    parts = [part for part in path.parts if part not in {"", "."}]
    if not parts:
        raise ValueError("Path is empty")
    if any(part == ".." for part in parts):
        raise ValueError("Path escapes the project root")
    return Path(*parts)


def is_protected(path):
    return path.name in PROTECTED_NAMES or path.parts[0] == "harness"


def configured_source_roots(config):
    """Return business source roots from platforms.*.path.

    New projects should keep all product code under workspace/. Existing projects
    may still use path: "."; in that case keep the historical src/ fallback so
    ordinary code edits do not become repository-wide by accident.
    """
    roots = []
    platforms = config.get("platforms") or {}
    if isinstance(platforms, dict):
        for platform in platforms.values():
            if not isinstance(platform, dict):
                continue
            raw_path = str(platform.get("path") or "").replace("\\", "/").strip()
            if not raw_path or raw_path == ".":
                continue
            root = normalize_path(raw_path)
            if root not in roots:
                roots.append(root)
    return roots or [Path("src")]


def is_under_root(path, root):
    return path == root or root in path.parents


def source_root_hint(roots):
    return ", ".join(root.as_posix() for root in roots)


def get_exclude_globs(config):
    quality_exclude = (config.get("quality") or {}).get("exclude_paths") or []
    arch_exclude = (config.get("architecture") or {}).get("exclude_paths") or []
    return quality_exclude + arch_exclude


def is_excluded(path, config):
    """Return True if path matches any exclude_paths glob (quality or architecture)."""
    exclude_globs = get_exclude_globs(config)
    if not exclude_globs:
        return False
    path_str = str(path).replace("\\", "/")
    for glob_pattern in exclude_globs:
        if fnmatch.fnmatch(path_str, glob_pattern) or fnmatch.fnmatch(
            path.name, glob_pattern
        ):
            return True
    return False


def get_layers(config):
    architecture = config.get("architecture") or {}
    raw_layers = architecture.get("layers") or []
    layers = []
    for item in raw_layers:
        if (
            isinstance(item, dict)
            and item.get("path") is not None
            and item.get("level") is not None
        ):
            layer_path = (
                str(item.get("path", "")).replace("\\", "/").strip().rstrip("/")
            )
            layers.append(
                {
                    "name": str(item.get("name") or Path(layer_path).name or "layer"),
                    "path": layer_path,
                    "level": int(item.get("level")),
                }
            )
    return sorted(layers, key=lambda item: len(item["path"]), reverse=True)


def matches_layer_path(path_text, layer_path):
    normalized_path = path_text.lower().strip("/")
    normalized_layer = layer_path.lower().strip("/")
    return normalized_path == normalized_layer or normalized_path.startswith(
        normalized_layer + "/"
    )


def layer_for_path(path, layers):
    text = path.as_posix().lower()
    for layer in layers:
        if matches_layer_path(text, layer["path"]):
            return layer
    return None


def layer_aliases(layer):
    path = layer["path"].lower().strip("/")
    return {
        path,
        path.replace("/", "."),
        Path(path).name.lower(),
        layer["name"].lower(),
    }


def changes_reference_layer(changes, layer):
    text = changes.lower().replace("\\", "/")
    dotted = text.replace("/", ".")
    for alias in layer_aliases(layer):
        if not alias:
            continue
        dotted_alias = alias.replace("/", ".")
        if alias in text or dotted_alias in dotted:
            return True
    return False


def naming_convention(config):
    for section in (
        config.get("naming"),
        config.get("naming_conventions"),
        (config.get("project") or {}).get("naming"),
    ):
        if not isinstance(section, dict):
            continue
        for key in ("files", "file", "file_name", "filename"):
            value = section.get(key)
            if isinstance(value, str) and value.strip():
                return value.strip()
    return None


def valid_name(file_name, convention):
    subject = file_name.rsplit(".", 1)[0]
    rules = {
        "snake_case": r"^[a-z][a-z0-9_]*$",
        "kebab-case": r"^[a-z][a-z0-9-]*$",
        "camelCase": r"^[a-z][A-Za-z0-9]*$",
        "PascalCase": r"^[A-Z][A-Za-z0-9]*$",
        "UPPER_SNAKE_CASE": r"^[A-Z][A-Z0-9_]*$",
    }
    pattern = rules.get(convention)
    return True if pattern is None else re.match(pattern, subject) is not None


def main():
    args = parse_args()
    match = ACTION_RE.match(args.action.strip())
    if not match:
        err(
            "--action",
            "Action format is invalid.",
            "Expected create/modify/delete file <relative-path>.",
            "Rewrite the action, for example: modify file workspace/apps/server/src/api/routes.py",
        )
        return 1

    verb = match.group(1).lower()
    try:
        config = load_config()
        target = normalize_path(match.group(2))
    except FileNotFoundError:
        err(
            "harness.yaml / harness.json",
            "Harness configuration was not found.",
            "verify_action.py needs harness.yaml or harness.json at the project root.",
            "Create harness.yaml, or generate it from harness.example.yaml during project initialization.",
        )
        return 1
    except (ValueError, json.JSONDecodeError) as error:
        err(
            "harness.yaml / harness.json",
            "Action metadata could not be read.",
            str(error),
            "Fix the path or configuration syntax and run verify_action.py again.",
        )
        return 1

    if is_protected(target) and not args.force:
        err(
            target.as_posix(),
            "Target is a protected file.",
            "AGENTS.md, CONSTRAINTS.md, and harness/ files change framework behavior.",
            "Re-run with --force only after confirming the protected change is required.",
        )
        return 1

    source_roots = configured_source_roots(config)
    if not any(is_under_root(target, root) for root in source_roots) and not is_protected(target):
        err(
            target.as_posix(),
            "Target is outside the configured source root.",
            "Regular code actions must stay inside platforms.*.path so product code stays separate from Harness infrastructure.",
            f"Target a file under {source_root_hint(source_roots)}, or use --force for an intentional protected path.",
        )
        return 1

    suffix = target.suffix.lower()
    if suffix not in SOURCE_SUFFIXES:
        err(
            target.as_posix(),
            f"File suffix '{suffix}' is not a recognized source suffix.",
            f"Only source files with suffixes in {sorted(SOURCE_SUFFIXES)} are allowed.",
            "Use a recognized source file extension, or place code under a supported language path.",
        )
        return 1

    if is_excluded(target, config):
        err(
            target.as_posix(),
            "Target path is excluded by quality.exclude_paths or architecture.exclude_paths.",
            "Excluded paths are not subject to architecture or quality validation.",
            "Remove the path from exclude_paths in harness.yaml, or target a different path.",
        )
        return 1

    convention = naming_convention(config)
    if (
        verb in {"create", "modify"}
        and convention
        and not valid_name(target.name, convention)
    ):
        err(
            target.name,
            f"File name violates the {convention} naming convention.",
            "Consistent names keep generated and hand-written modules predictable.",
            f"Rename the file so its base name matches {convention} before continuing.",
        )
        return 1

    layers = get_layers(config)
    source_layer = layer_for_path(target, layers)
    if source_layer and "import" in args.changes.lower():
        for imported_layer in layers:
            if imported_layer == source_layer:
                continue
            if not changes_reference_layer(args.changes, imported_layer):
                continue
            if int(imported_layer["level"]) < int(source_layer["level"]):
                err(
                    target.as_posix(),
                    f"Would import from layer {imported_layer['name']}.",
                    f'Layer "{source_layer["name"]}" (level {source_layer["level"]}) cannot import from layer "{imported_layer["name"]}" (level {imported_layer["level"]}).',
                    "Move shared logic into a lower-level or common module, then import that module instead.",
                )
                return 1

    info(
        "Action",
        "Action is valid.",
        "All pre-action checks passed.",
        "Proceed with the planned file operation.",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
