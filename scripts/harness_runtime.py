# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Shared runtime path helpers for Harness template and target repositories."""

import importlib
import os
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALID_REPO_ROLES = {"template", "target"}
ADVANCED_YAML_PATTERNS = [
    (re.compile(r"(^|[\s:\[,])&[A-Za-z0-9_-]+"), "anchors"),
    (re.compile(r"(^|[\s:\[,])\*[A-Za-z0-9_-]+"), "aliases"),
    (re.compile(r"(^|\s)<<\s*:"), "merge keys"),
    (re.compile(r":\s*[|>](?:\s|$)|-\s*[|>](?:\s|$)"), "block scalars"),
]


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
    return data if isinstance(data, dict) else {}


def mask_quoted(line):
    out = []
    in_single = False
    in_double = False
    for char in line:
        if char == "'" and not in_double:
            in_single = not in_single
            out.append(char)
        elif char == '"' and not in_single:
            in_double = not in_double
            out.append(char)
        elif in_single or in_double:
            out.append(" ")
        else:
            out.append(char)
    return "".join(out)


def unsupported_fallback_yaml_features(text):
    features = []
    for raw_line in text.splitlines():
        line = mask_quoted(strip_comment(raw_line))
        if not line.strip():
            continue
        for pattern, label in ADVANCED_YAML_PATTERNS:
            if pattern.search(line) and label not in features:
                features.append(label)
    return features


def ensure_fallback_yaml_supported(text, path):
    features = unsupported_fallback_yaml_features(text)
    if not features:
        return
    feature_text = ", ".join(features)
    raise ValueError(
        f"{path} uses advanced YAML features ({feature_text}) that require PyYAML. "
        "Install PyYAML or rewrite the config using simple mappings/lists/scalars."
    )


def load_yaml(path):
    if not path.exists():
        return {}
    text = path.read_text(encoding="utf-8")
    try:
        module = importlib.import_module("yaml")
        loader = getattr(module, "safe_load", None)
    except ImportError:
        loader = None
    if callable(loader):
        try:
            data = loader(text)
        except Exception as error:
            raise ValueError(f"{path} could not be parsed by PyYAML: {error}") from error
    else:
        ensure_fallback_yaml_supported(text, path)
        data = parse_yaml(text)
    return data if isinstance(data, dict) else {}


def git_tracked(root, rel_path):
    result = subprocess.run(
        ["git", "ls-files", "--error-unmatch", rel_path],
        cwd=root,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result.returncode == 0


def role_from_config(data):
    runtime = data.get("harness_runtime") if isinstance(data, dict) else {}
    if not isinstance(runtime, dict):
        return ""
    role = str(runtime.get("repo_role") or "").strip().lower()
    return role if role in VALID_REPO_ROLES else ""


def repo_role(root=ROOT):
    root = Path(root)
    env_role = os.environ.get("HARNESS_REPO_ROLE", "").strip().lower()
    if env_role in VALID_REPO_ROLES:
        return env_role

    for name in ("harness.local.yaml", "harness.yaml", "harness.example.yaml"):
        # Role detection is best-effort path selection; a broken config must not
        # crash it. Consumers that need the config still get the loud failure.
        try:
            role = role_from_config(load_yaml(root / name))
        except ValueError:
            continue
        if role:
            return role

    has_example = (root / "harness.example.yaml").exists()
    has_harness_yaml = (root / "harness.yaml").exists()
    if has_harness_yaml:
        return "target"
    if has_example:
        return "template"
    return "target"


def is_template_repo(root=ROOT):
    return repo_role(root) == "template"


def local_root(root=ROOT):
    return Path(root) / "harness" / ".local"


def runtime_file(root, target_relative, local_relative=None):
    root = Path(root)
    if is_template_repo(root):
        return local_root(root) / (local_relative or target_relative)
    return root / target_relative


def performance_path(root=ROOT):
    return runtime_file(
        root,
        "harness/memory/performance.md",
        "memory/performance.md",
    )


def trace_path(root, filename):
    return runtime_file(root, f"harness/trace/{filename}", f"trace/{filename}")


def upgrade_report_path(root, filename):
    return runtime_file(
        root,
        f"harness/trace/{filename}",
        f"upgrade-reports/{filename}",
    )


def dream_report_path(root, filename):
    return runtime_file(
        root,
        f"harness/dream/reports/{filename}",
        f"dream/reports/{filename}",
    )


def hook_event_path(root, filename):
    return runtime_file(
        root,
        f"harness/trace/hook-events/{filename}",
        f"hook-events/{filename}",
    )


def display_path(root, path):
    root = Path(root)
    try:
        return Path(path).relative_to(root).as_posix()
    except ValueError:
        return Path(path).as_posix()
