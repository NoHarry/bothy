# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Plan or apply Harness framework updates to an existing project."""

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
from datetime import datetime
from pathlib import Path

from harness_runtime import load_yaml as load_harness_yaml, upgrade_report_path


ROOT = Path(__file__).resolve().parents[1]
VERSION_RE = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:[-+][0-9A-Za-z.-]+)?$")
COMMIT_RE = re.compile(r"^[0-9a-fA-F]{40}$")

FRAMEWORK_AGENT_MAP_ITEMS = [
    ".agent-harness/AGENTS.md",
    ".agent-harness/skills/AGENTS.md",
    ".githooks/AGENTS.md",
    ".review/AGENTS.md",
    "doc/AGENTS.md",
    "harness/AGENTS.md",
    "harness/doc-local/AGENTS.md",
    "harness/docs/AGENTS.md",
    "harness/dream/AGENTS.md",
    "harness/evals/AGENTS.md",
    "harness/memory/AGENTS.md",
    "harness/trace/AGENTS.md",
    "scripts/AGENTS.md",
    "templates/AGENTS.md",
]

FRAMEWORK_ITEMS = [
    *FRAMEWORK_AGENT_MAP_ITEMS,
    ".agent-harness",
    ".agents/skills",
    ".claude/skills",
    ".githooks",
    ".opencode/skills",
    ".review/rules/README.md",
    ".review/rules/harness-sync.md",
    "harness/docs",
    "harness/dream/README.md",
    "harness/evals",
    "harness/policy.example.yaml",
    "harness/requirements.txt",
    "harness/VERSION",
    "harness.example.yaml",
    "scripts/agent_runtime.py",
    "scripts/dream_runner.py",
    "scripts/hook_bus.py",
    "scripts/install_git_hooks.py",
    "scripts/harness_runtime.py",
    "scripts/lint_agent_entrypoints.py",
    "scripts/lint_deps.py",
    "scripts/lint_goal_contract.py",
    "scripts/lint_harness_config.py",
    "scripts/lint_memory.py",
    "scripts/lint_prd_review_signoff.py",
    "scripts/lint_quality.py",
    "scripts/lint_template_version.py",
    "scripts/lint_template_clean.py",
    "scripts/sync_skills.py",
    "scripts/test_harness_runtime_yaml.py",
    "scripts/test_goal_contract.py",
    "scripts/test_lint_deps.py",
    "scripts/test_sync_skills.py",
    "scripts/test_upgrade_harness.py",
    "scripts/test_validate.py",
    "scripts/upgrade_harness.py",
    "scripts/validate.py",
    "scripts/validate_harness_sync.py",
    "scripts/validate_workflow.py",
    "scripts/verify_action.py",
    "scripts/workspace_doctor.py",
    "templates",
]

ADD_ONLY_ITEMS = [
    "CLAUDE.md",
    "WORKFLOW.md",
]

PROTECTED_ITEMS = [
    "AGENTS.md",
    "BLACKBOARD.md",
    "CONSTRAINTS.md",
    ".gitignore",
    "doc",
    "docs",
    "harness.yaml",
    "harness/policy.yaml",
    "harness/memory",
    "harness/trace",
]

SKIP_PARTS = {".git", ".local", "__pycache__", ".ruff_cache", ".mypy_cache", ".pytest_cache"}
SKIP_SUFFIXES = {".pyc", ".pyo"}
REMOTE_PREFIXES = ("http://", "https://", "ssh://", "git@")


def posix(path):
    return path.as_posix().strip("/")


def is_under(rel_path, item):
    rel = rel_path.strip("/")
    item = item.strip("/")
    return rel == item or rel.startswith(item + "/")


def is_skipped(path):
    return bool(set(path.parts) & SKIP_PARTS) or path.suffix in SKIP_SUFFIXES


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


def load_yaml(path):
    return load_harness_yaml(path)


def target_framework_config(target):
    for name in ("harness.yaml", "harness.example.yaml"):
        path = target / name
        if not path.exists():
            continue
        data = load_yaml(path)
        framework = data.get("harness_framework")
        return framework if isinstance(framework, dict) else {}
    return {}


def template_version(root):
    version_path = root / "harness" / "VERSION"
    if version_path.exists():
        version = version_path.read_text(encoding="utf-8").strip()
        if version:
            return version
    framework = target_framework_config(root)
    version = str(framework.get("template_version") or "").strip()
    return version or "unknown"


def parse_semver(version):
    match = VERSION_RE.fullmatch(str(version).strip())
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def compare_template_versions(source_version, target_version):
    source = str(source_version or "").strip()
    target = str(target_version or "").strip()
    if not target or target == "unknown":
        return "target-version-missing"
    if source == target:
        return "same-version"
    source_tuple = parse_semver(source)
    target_tuple = parse_semver(target)
    if source_tuple is None:
        return "source-version-unparseable"
    if target_tuple is None:
        return "target-version-legacy"
    if source_tuple > target_tuple:
        return "upgrade-available"
    if source_tuple < target_tuple:
        return "target-newer-than-source"
    return "version-metadata-diff"


def ensure_version_manual_action(actions, target, target_version, source_version, version_status):
    if not (target / "harness.yaml").exists():
        return actions
    if version_status in {"same-version", "target-newer-than-source"}:
        return actions
    if any(item["action"] == "MANUAL" and item["path"] == "harness.yaml" for item in actions):
        return actions
    return [
        *actions,
        {
            "action": "MANUAL",
            "path": "harness.yaml",
            "reason": f"template_version {target_version or 'unknown'} -> {source_version}",
        },
    ]


def is_remote_source(source_text):
    source = source_text.strip()
    return source.startswith(REMOTE_PREFIXES) or (
        "@" in source and ":" in source and not Path(source).exists()
    )


def git_output(cwd, args):
    result = subprocess.run(
        ["git", *args],
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def git_dirty(cwd):
    result = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        return None
    return bool(result.stdout.strip())


def normalize_source_commit(value):
    commit = str(value or "").strip()
    if commit.lower() in {"none", "null"}:
        return ""
    if commit and not COMMIT_RE.fullmatch(commit):
        raise RuntimeError(
            "harness_framework.source_commit must be a 40-character git commit SHA."
        )
    return commit.lower()


def source_identity(source, source_text, source_ref, source_commit, source_kind):
    resolved_commit = git_output(source, ["rev-parse", "HEAD"])
    resolved_ref = git_output(source, ["rev-parse", "--abbrev-ref", "HEAD"])
    if resolved_ref == "HEAD":
        resolved_ref = ""
    dirty = False if source_kind == "remote" else git_dirty(source)
    identity = {
        "source": source_text,
        "source_kind": source_kind,
        "requested_ref": source_ref,
        "requested_commit": source_commit,
        "resolved_path": str(source),
        "resolved_ref": resolved_ref,
        "resolved_commit": resolved_commit,
        "source_dirty": dirty,
    }
    if source_commit and resolved_commit.lower() != source_commit:
        actual = resolved_commit or "unavailable"
        raise RuntimeError(
            "Harness source commit mismatch: "
            f"requested {source_commit}, resolved {actual}. "
            "Refuse to apply an upgrade from an unpinned or moved source."
        )
    return identity


def resolve_source(source_text, source_ref, source_commit=""):
    if not is_remote_source(source_text):
        source = Path(source_text).resolve()
        if not source.exists():
            return source, None, {
                "source": source_text,
                "source_kind": "local",
                "requested_ref": source_ref,
                "requested_commit": source_commit,
                "resolved_path": str(source),
                "resolved_ref": "",
                "resolved_commit": "",
                "source_dirty": None,
            }
        return (
            source,
            None,
            source_identity(source, source_text, source_ref, source_commit, "local"),
        )

    temp_root = Path(tempfile.mkdtemp(prefix="harness-upgrade-"))
    clone_dir = temp_root / "template"
    command = ["git", "clone", "--depth", "1"]
    if source_ref:
        command.extend(["--branch", source_ref])
    command.extend([source_text, str(clone_dir)])
    result = subprocess.run(
        command,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        shutil.rmtree(temp_root, ignore_errors=True)
        message = result.stderr.strip() or result.stdout.strip()
        raise RuntimeError(f"Failed to clone harness source: {message}")
    return (
        clone_dir,
        temp_root,
        source_identity(clone_dir, source_text, source_ref, source_commit, "remote"),
    )


def iter_item_files(root, item):
    path = root / item
    if not path.exists():
        return []
    if path.is_file():
        return [path]
    return sorted(p for p in path.rglob("*") if p.is_file() and not is_skipped(p))


def comparable_bytes(path):
    data = path.read_bytes()
    if b"\0" not in data:
        return data.replace(b"\r\n", b"\n")
    return data


def sha256(path):
    digest = hashlib.sha256()
    digest.update(comparable_bytes(path))
    return digest.hexdigest()


def build_plan(source, target):
    actions = []
    seen = set()
    framework_agent_maps = set(FRAMEWORK_AGENT_MAP_ITEMS)

    for item in FRAMEWORK_ITEMS:
        for source_file in iter_item_files(source, item):
            rel = posix(source_file.relative_to(source))
            if rel in seen:
                continue
            seen.add(rel)
            target_file = target / rel
            if not target_file.exists():
                actions.append({"action": "ADD", "path": rel})
            elif sha256(source_file) == sha256(target_file):
                actions.append({"action": "SKIP", "path": rel})
            else:
                actions.append({"action": "UPDATE", "path": rel})

    for item in ADD_ONLY_ITEMS:
        source_file = source / item
        target_file = target / item
        if not source_file.exists() or not source_file.is_file():
            continue
        if not target_file.exists():
            actions.append({"action": "ADD", "path": item})
        elif sha256(source_file) == sha256(target_file):
            actions.append({"action": "SKIP", "path": item})
        else:
            actions.append({"action": "MANUAL", "path": item})

    for item in PROTECTED_ITEMS:
        source_path = source / item
        target_path = target / item
        if not source_path.exists() or not target_path.exists():
            continue
        if source_path.is_file() and target_path.is_file():
            if sha256(source_path) != sha256(target_path):
                actions.append({"action": "MANUAL", "path": item})
            continue
        source_files = iter_item_files(source, item)
        for source_file in source_files:
            rel = posix(source_file.relative_to(source))
            if rel in framework_agent_maps:
                continue
            target_file = target / rel
            if target_file.exists() and target_file.is_file() and sha256(source_file) != sha256(target_file):
                actions.append({"action": "MANUAL", "path": rel})

    return actions


def backup_file(target, rel, backup_root):
    source = target / rel
    if not source.exists():
        return ""
    backup = backup_root / rel
    backup.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, backup)
    return posix(backup.relative_to(target))


def apply_plan(source, target, actions):
    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    backup_root = target / "harness" / "upgrade-backups" / timestamp
    applied = []
    for item in actions:
        action = item["action"]
        rel = item["path"]
        if action not in {"ADD", "UPDATE"}:
            continue
        source_file = source / rel
        target_file = target / rel
        source_sha = sha256(source_file)
        previous_sha = sha256(target_file) if target_file.exists() else ""
        target_file.parent.mkdir(parents=True, exist_ok=True)
        backup = backup_file(target, rel, backup_root) if action == "UPDATE" else ""
        shutil.copy2(source_file, target_file)
        applied_item = {
            "action": action,
            "path": rel,
            "backup": backup,
            "source_sha256": source_sha,
        }
        if previous_sha:
            applied_item["previous_sha256"] = previous_sha
        if backup:
            applied_item["backup_sha256"] = sha256(target / backup)
        applied.append(applied_item)
    return applied


def summarize(actions):
    counts = {}
    for item in actions:
        counts[item["action"]] = counts.get(item["action"], 0) + 1
    return counts


def write_report(
    target,
    mode,
    actions,
    applied,
    source_text,
    resolved_source,
    source_id,
    cleanup_root,
    target_version,
    source_version,
    version_status,
):
    path = upgrade_report_path(
        target, f"{datetime.now().date().isoformat()}-harness-upgrade-{mode}.md"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    json_path = path.with_suffix(".json")
    counts = summarize(actions)
    lines = [
        "# Harness Upgrade Report",
        "",
        f"- Mode: {mode}",
        f"- Source: {source_text}",
        f"- Resolved source: {resolved_source}",
        f"- Source kind: {source_id.get('source_kind') or 'unknown'}",
        f"- Requested ref: {source_id.get('requested_ref') or 'none'}",
        f"- Requested commit: {source_id.get('requested_commit') or 'none'}",
        f"- Resolved ref: {source_id.get('resolved_ref') or 'none'}",
        f"- Resolved commit: {source_id.get('resolved_commit') or 'unavailable'}",
        f"- Source dirty: {source_id.get('source_dirty')}",
        f"- Target template version: {target_version}",
        f"- Source template version: {source_version}",
        f"- Version status: {version_status}",
        f"- Source cleanup: {'kept' if cleanup_root else 'not needed or cleaned after run'}",
        f"- Generated: {datetime.now().isoformat(timespec='seconds')}",
        "",
        "## Summary",
        "",
    ]
    for key in ("ADD", "UPDATE", "MANUAL", "SKIP"):
        lines.append(f"- {key}: {counts.get(key, 0)}")
    lines.extend(["", "## Actions", ""])
    for item in actions:
        if item["action"] == "SKIP":
            continue
        suffix = f" - {item['reason']}" if item.get("reason") else ""
        lines.append(f"- {item['action']}: `{item['path']}`{suffix}")
    if applied:
        lines.extend(["", "## Applied", ""])
        for item in applied:
            details = [f"source_sha256: `{item.get('source_sha256', '')}`"]
            if item.get("previous_sha256"):
                details.append(f"previous_sha256: `{item['previous_sha256']}`")
            if item.get("backup"):
                details.append(f"backup: `{item['backup']}`")
                details.append(f"backup_sha256: `{item.get('backup_sha256', '')}`")
            lines.append(
                f"- {item['action']}: `{item['path']}` ({'; '.join(details)})"
            )
    lines.extend(
        [
            "",
            "## Next Steps",
            "",
            "- Review MANUAL items and convert them to owner actions or deliberate project edits.",
            "- If the source version is newer, update `harness.yaml` `harness_framework.template_version` after MANUAL review.",
            "- Run `python scripts/workspace_doctor.py --fix-safe --run-checks --write-trace`.",
            "- Run `python scripts/validate_workflow.py`, `python scripts/validate_harness_sync.py`, and `python scripts/validate.py --dry-run`.",
            "- Submit through `commit-review`.",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")
    payload = {
        "summary": counts,
        "source": source_text,
        "resolved_source": str(resolved_source),
        "source_identity": source_id,
        "template_version": {
            "target": target_version,
            "source": source_version,
            "status": version_status,
        },
        "remote_source_cleaned": bool(cleanup_root is None and source_id.get("source_kind") == "remote"),
        "actions": actions,
        "applied": applied,
        "report": posix(path.relative_to(target)),
        "report_json": posix(json_path.relative_to(target)),
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path, json_path


def print_version_check(target_version, source_version, version_status):
    print(
        f"[VERSION] target={target_version or 'unknown'} "
        f"source={source_version or 'unknown'} status={version_status}"
    )
    if version_status == "same-version":
        print("[VERSION] Same template version; apply only to repair framework drift.")
    elif version_status == "target-newer-than-source":
        print("[WARN] Target records a newer template version than the selected source.")
    elif version_status == "target-version-legacy":
        print("[VERSION] Target version is legacy/non-semver; treat this as an upgrade candidate.")


def print_plan(actions, applied):
    counts = summarize(actions)
    print("[HARNESS-UPGRADE] Summary")
    for key in ("ADD", "UPDATE", "MANUAL", "SKIP"):
        print(f"  {key}: {counts.get(key, 0)}")
    for item in actions:
        if item["action"] != "SKIP":
            suffix = f" reason={item['reason']}" if item.get("reason") else ""
            print(f"[{item['action']}] {item['path']}{suffix}")
    for item in applied:
        backup = f" backup={item['backup']}" if item.get("backup") else ""
        print(f"[APPLIED] {item['path']}{backup}")


def parse_args():
    parser = argparse.ArgumentParser(description="Plan or apply Harness template upgrades.")
    _ = parser.add_argument(
        "--source",
        help="Latest harness template root or git URL. Defaults to target harness_framework.source.",
    )
    _ = parser.add_argument(
        "--source-ref",
        help="Optional branch/tag for remote source. Defaults to target harness_framework.source_ref.",
    )
    _ = parser.add_argument(
        "--source-commit",
        help="Optional 40-character commit SHA pin. Defaults to target harness_framework.source_commit.",
    )
    _ = parser.add_argument("--target", default=".", help="Target project root. Defaults to cwd.")
    _ = parser.add_argument("--mode", choices=["plan", "apply"], default="plan")
    _ = parser.add_argument("--write-report", action="store_true")
    _ = parser.add_argument(
        "--keep-source",
        action="store_true",
        help="Keep cloned remote template under temp for troubleshooting.",
    )
    _ = parser.add_argument("--json", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    target = Path(args.target).resolve()
    if not target.exists():
        print(f"[FAIL] Target project does not exist: {target}")
        return 1
    framework = target_framework_config(target)
    source_text = str(args.source or framework.get("source") or "").strip()
    source_ref = str(args.source_ref or framework.get("source_ref") or "").strip()
    try:
        source_commit = normalize_source_commit(
            args.source_commit or framework.get("source_commit") or ""
        )
    except RuntimeError as error:
        print(f"[FAIL] {error}")
        return 1
    if not source_text:
        print("[FAIL] Missing source. Pass --source or set harness_framework.source in target harness.yaml.")
        return 1

    cleanup_root = None
    try:
        source, cleanup_root, source_id = resolve_source(
            source_text, source_ref, source_commit
        )
        if not source.exists():
            print(f"[FAIL] Source template does not exist: {source}")
            return 1
        source_version = template_version(source)
        target_version = str(framework.get("template_version") or "").strip() or "unknown"
        version_status = compare_template_versions(source_version, target_version)
        actions = build_plan(source, target)
        actions = ensure_version_manual_action(
            actions, target, target_version, source_version, version_status
        )
        applied = apply_plan(source, target, actions) if args.mode == "apply" else []
        report = ""
        report_json = ""
        if args.write_report:
            report_path, report_json_path = write_report(
                target,
                args.mode,
                actions,
                applied,
                source_text,
                source,
                source_id,
                cleanup_root if args.keep_source else None,
                target_version,
                source_version,
                version_status,
            )
            report = posix(report_path.relative_to(target))
            report_json = posix(report_json_path.relative_to(target))
        if args.json:
            print(
                json.dumps(
                    {
                        "summary": summarize(actions),
                        "source": source_text,
                        "resolved_source": str(source),
                        "source_identity": source_id,
                        "template_version": {
                            "target": target_version,
                            "source": source_version,
                            "status": version_status,
                        },
                        "remote_source_cleaned": bool(cleanup_root and not args.keep_source),
                        "actions": actions,
                        "applied": applied,
                        "report": report,
                        "report_json": report_json,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
        else:
            print(f"[SOURCE] {source_text}")
            print(f"[RESOLVED] {source}")
            print(
                f"[SOURCE-IDENTITY] kind={source_id.get('source_kind')} "
                f"ref={source_id.get('resolved_ref') or 'none'} "
                f"commit={source_id.get('resolved_commit') or 'unavailable'} "
                f"dirty={source_id.get('source_dirty')}"
            )
            print_version_check(target_version, source_version, version_status)
            print_plan(actions, applied)
            if cleanup_root and not args.keep_source:
                print("[CLEANUP] Remote template clone will be removed after this run.")
            elif cleanup_root:
                print(f"[CLEANUP] Remote template clone kept at {cleanup_root}")
            if report:
                print(f"[REPORT] {report}")
                print(f"[REPORT-JSON] {report_json}")
        manual = [item for item in actions if item["action"] == "MANUAL"]
        return 2 if manual else 0
    except RuntimeError as error:
        print(f"[FAIL] {error}")
        return 1
    finally:
        if cleanup_root and not args.keep_source:
            shutil.rmtree(cleanup_root, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
