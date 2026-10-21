# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Check or write multi-tool Harness skill projections."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path

from harness_runtime import ROOT, display_path, load_yaml


TARGETS = {
    "codex": Path(".agents/skills"),
    "claude": Path(".claude/skills"),
    "opencode": Path(".opencode/skills"),
}

REGISTRY_NAMES = ("registry.yaml", "registry.project.yaml")


def file_hash(path):
    digest = hashlib.sha256()
    digest.update(path.read_bytes().replace(b"\r\n", b"\n"))
    return digest.hexdigest()


def is_skipped(path):
    return "__pycache__" in path.parts or path.suffix in {".pyc", ".pyo"}


def iter_files(root):
    if not root.exists():
        return []
    return sorted(path for path in root.rglob("*") if path.is_file() and not is_skipped(path))


def directory_fingerprint(root):
    files = {}
    for path in iter_files(root):
        rel = path.relative_to(root).as_posix()
        files[rel] = file_hash(path)
    return files


def active_registry_paths():
    root = ROOT / ".agent-harness" / "skills"
    return [root / name for name in REGISTRY_NAMES if (root / name).exists()]


def load_registries():
    registries = []
    for path in active_registry_paths():
        data = load_yaml(path)
        skills = data.get("skills") if isinstance(data, dict) else []
        registries.append((path, skills if isinstance(skills, list) else []))
    return registries


def projected_skill_names(base):
    root = ROOT / base
    if not root.exists():
        return set()
    return {path.parent.name for path in root.glob("*/SKILL.md")}


def selected_targets(target):
    if target == "all":
        return TARGETS
    return {target: TARGETS[target]}


def active_skill_entries(registries):
    entries = []
    for registry_path, skills in registries:
        for item in skills:
            if not isinstance(item, dict):
                entries.append((registry_path, None, "Registry skill entry must be a mapping."))
                continue
            status = str(item.get("status") or "active").strip().lower()
            if status not in {"active", "proposed"}:
                continue
            name = str(item.get("name") or "").strip()
            source = str(item.get("source") or "").strip()
            if not name or not source:
                entries.append((registry_path, None, f"Registry entry missing name/source: {item}"))
                continue
            entries.append((registry_path, item, ""))
    return entries


def append_registry_errors(registries, messages):
    seen = {}
    ok = True
    for registry_path, item, error in active_skill_entries(registries):
        if error:
            messages.append({"level": "error", "message": error})
            ok = False
            continue
        name = str(item.get("name")).strip()
        previous = seen.get(name)
        if previous:
            messages.append(
                {
                    "level": "error",
                    "message": (
                        "Duplicate skill registry entry "
                        f"'{name}' in {display_path(ROOT, registry_path)}; "
                        f"already declared in {display_path(ROOT, previous)}"
                    ),
                }
            )
            ok = False
        else:
            seen[name] = registry_path
    return ok


def check_existing_projection_shape(messages):
    for target, base in TARGETS.items():
        root = ROOT / base
        if not root.exists():
            messages.append({"level": "warn", "message": f"{target} projection is missing: {base.as_posix()}"})
            continue
        missing = [path.name for path in root.iterdir() if path.is_dir() and not (path / "SKILL.md").exists()]
        if missing:
            messages.append(
                {
                    "level": "error",
                    "message": f"{target} projection has skill dirs without SKILL.md: {', '.join(sorted(missing))}",
                }
            )
        else:
            messages.append(
                {
                    "level": "info",
                    "message": f"{target} projection has {len(projected_skill_names(base))} skill(s).",
                }
            )


def projection_targets_for(item, target):
    targets = item.get("targets") if isinstance(item.get("targets"), dict) else {}
    return {
        name: base
        for name, base in selected_targets(target).items()
        if bool(targets.get(name))
    }


def check_registry_projection(registries, messages, target):
    expected_by_target = {name: set() for name in selected_targets(target)}
    if not append_registry_errors(registries, messages):
        return
    for registry_path, item, error in active_skill_entries(registries):
        if error:
            continue
        registry_root = registry_path.parent
        name = str(item.get("name")).strip()
        source = str(item.get("source")).strip()
        source_skill = registry_root / source / "SKILL.md"
        if not source_skill.exists():
            messages.append({"level": "error", "message": f"Missing source skill: {display_path(ROOT, source_skill)}"})
            continue
        source_dir = registry_root / source
        source_fingerprint = directory_fingerprint(source_dir)
        for target_name, base in projection_targets_for(item, target).items():
            expected_by_target[target_name].add(name)
            target_dir = ROOT / base / name
            if not target_dir.exists():
                messages.append({"level": "error", "message": f"Missing {target_name} projection: {display_path(ROOT, target_dir)}"})
                continue
            target_fingerprint = directory_fingerprint(target_dir)
            if target_fingerprint != source_fingerprint:
                messages.append({"level": "error", "message": f"{target_name} projection drifted: {display_path(ROOT, target_dir)}"})
            else:
                messages.append({"level": "info", "message": f"{target_name} projection OK: {name}"})
    for target_name, base in selected_targets(target).items():
        root = ROOT / base
        if not root.exists():
            if expected_by_target[target_name]:
                messages.append({"level": "error", "message": f"Missing projection root: {base.as_posix()}"})
            continue
        extra = projected_skill_names(base) - expected_by_target[target_name]
        if extra:
            messages.append(
                {
                    "level": "error",
                    "message": f"{target_name} projection has unmanaged skill(s): {', '.join(sorted(extra))}",
                }
            )


def safe_replace_dir(source_dir, target_dir, target_root):
    target_root = target_root.resolve()
    target_dir = target_dir.resolve()
    try:
        relative = target_dir.relative_to(target_root)
    except ValueError as error:
        raise RuntimeError(f"Refusing to write outside projection root: {target_dir}") from error
    if not str(relative):
        raise RuntimeError("Refusing to replace projection root.")
    if target_dir.exists():
        shutil.rmtree(target_dir)
    shutil.copytree(source_dir, target_dir)


def write_registry_projection(registries, target, messages):
    if not append_registry_errors(registries, messages):
        return
    for registry_path, item, error in active_skill_entries(registries):
        if error:
            continue
        registry_root = registry_path.parent
        name = str(item.get("name")).strip()
        source = str(item.get("source")).strip()
        source_dir = registry_root / source
        if not (source_dir / "SKILL.md").exists():
            messages.append({"level": "error", "message": f"Missing source skill: {display_path(ROOT, source_dir / 'SKILL.md')}"})
            continue
        for target_name, base in projection_targets_for(item, target).items():
            target_root = ROOT / base
            target_dir = target_root / name
            target_root.mkdir(parents=True, exist_ok=True)
            safe_replace_dir(source_dir, target_dir, target_root)
            messages.append({"level": "info", "message": f"Wrote {target_name} projection: {display_path(ROOT, target_dir)}"})


def parse_args():
    parser = argparse.ArgumentParser(description="Check Harness skill projection drift.")
    _ = parser.add_argument("--check", action="store_true", help="Check projection drift.")
    _ = parser.add_argument("--write", action="store_true", help="Write projections from .agent-harness/skills/src.")
    _ = parser.add_argument("--target", choices=["all", *sorted(TARGETS)], default="all")
    _ = parser.add_argument("--json", action="store_true", help="Print machine-readable output.")
    return parser.parse_args()


def main():
    args = parse_args()
    registries = load_registries()
    messages = []
    if registries:
        for registry_path, _ in registries:
            label = "project registry" if registry_path.name == "registry.project.yaml" else "registry"
            messages.append({"level": "info", "message": f"Using {label}: {display_path(ROOT, registry_path)}"})
        if args.write:
            write_registry_projection(registries, args.target, messages)
        check_registry_projection(registries, messages, args.target)
    elif args.write:
        messages.append(
            {
                "level": "error",
                "message": "Cannot write projections without .agent-harness/skills/registry.yaml or registry.project.yaml.",
            }
        )
    else:
        messages.append(
            {
                "level": "warn",
                "message": "No active .agent-harness/skills registry found; running projection-shape checks only.",
            }
        )
        check_existing_projection_shape(messages)
    failed = any(item["level"] == "error" for item in messages)
    if args.json:
        print(json.dumps({"ok": not failed, "messages": messages}, ensure_ascii=False, indent=2))
    else:
        for item in messages:
            print(f"[{item['level'].upper()}] {item['message']}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
