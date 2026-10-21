# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Validate repo-level Harness memory governance metadata."""

import re
from pathlib import Path

from harness_runtime import ROOT, load_yaml


MEMORY_DIR = ROOT / "harness" / "memory"
INDEX_PATH = MEMORY_DIR / "index.yaml"

ENTRY_ID_RE = re.compile(r"\bmem-\d{4}-\d{2}-\d{2}-\d{3}\b")
VALID_TYPES = {
    "semantic",
    "procedural",
    "failure",
    "reflective",
    "policy",
    "decision",
}
VALID_SCOPES = {"template", "project", "local"}
VALID_STATUSES = {
    "candidate",
    "active",
    "superseded",
    "deprecated",
    "promoted_to_rule",
}
VALID_CONFIDENCE = {"low", "medium", "high"}
FILE_EVIDENCE_KEYS = {"trace", "script", "file", "doc", "rule", "memory"}


def fail(message):
    print(f"[FAIL] {message}")


def ok(message):
    print(f"[OK] {message}")


def rel(path):
    return path.relative_to(ROOT).as_posix()


def read_text(path):
    return path.read_text(encoding="utf-8", errors="ignore")


def markdown_memory_files():
    if not MEMORY_DIR.exists():
        return []
    files = []
    for path in MEMORY_DIR.rglob("*.md"):
        parts = {part.lower() for part in path.relative_to(MEMORY_DIR).parts}
        if "archive" in parts or path.name.lower() == "readme.md":
            continue
        files.append(path)
    return sorted(files)


def markdown_entry_ids():
    ids = {}
    for path in markdown_memory_files():
        text = read_text(path)
        found = set(ENTRY_ID_RE.findall(text))
        for entry_id in found:
            ids.setdefault(entry_id, []).append(path)
    return ids


def coerce_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def path_without_line_suffix(value):
    text = str(value).strip()
    # Strip markdown-ish line suffixes such as path.md:42.
    match = re.match(r"^(.+?):\d+$", text)
    return match.group(1) if match else text


def looks_like_repo_path(value):
    text = path_without_line_suffix(value)
    return text.startswith(
        (
            ".agent-harness/",
            ".agents/",
            ".claude/",
            ".opencode/",
            ".review/",
            "AGENTS.md",
            "CONSTRAINTS.md",
            "README.md",
            "harness/",
            "scripts/",
            "templates/",
        )
    )


def evidence_file_values(evidence):
    values = []
    for item in coerce_list(evidence):
        if isinstance(item, dict):
            for key, value in item.items():
                if key in FILE_EVIDENCE_KEYS:
                    values.append(str(value))
        elif isinstance(item, str) and looks_like_repo_path(item):
            values.append(item)
    return values


def validate_entry(entry, index, markdown_ids):
    errors = []
    prefix = f"entries[{index}]"
    if not isinstance(entry, dict):
        return [f"{prefix} must be an object"]

    entry_id = str(entry.get("id") or "").strip()
    file_name = str(entry.get("file") or "").strip()
    required = ["id", "file", "title", "type", "scope", "status", "confidence"]
    for key in required:
        if not str(entry.get(key) or "").strip():
            errors.append(f"{prefix}.{key} is required")

    if entry_id and not ENTRY_ID_RE.fullmatch(entry_id):
        errors.append(f"{prefix}.id must match mem-YYYY-MM-DD-NNN")

    if entry.get("type") and entry["type"] not in VALID_TYPES:
        errors.append(f"{prefix}.type must be one of {sorted(VALID_TYPES)}")
    if entry.get("scope") and entry["scope"] not in VALID_SCOPES:
        errors.append(f"{prefix}.scope must be one of {sorted(VALID_SCOPES)}")
    if entry.get("status") and entry["status"] not in VALID_STATUSES:
        errors.append(f"{prefix}.status must be one of {sorted(VALID_STATUSES)}")
    if entry.get("confidence") and entry["confidence"] not in VALID_CONFIDENCE:
        errors.append(
            f"{prefix}.confidence must be one of {sorted(VALID_CONFIDENCE)}"
        )
    if entry.get("scope") == "local":
        errors.append(f"{prefix}.scope local memory belongs under harness/.local/")

    if file_name:
        file_path = MEMORY_DIR / file_name
        if file_path.name.lower() in {"readme.md", "index.yaml"}:
            errors.append(f"{prefix}.file must point to a memory content file")
        if "archive" in {part.lower() for part in Path(file_name).parts}:
            errors.append(f"{prefix}.file must not point into archive/")
        if not file_path.exists():
            errors.append(f"{prefix}.file does not exist: harness/memory/{file_name}")
        elif entry_id and entry_id not in read_text(file_path):
            errors.append(f"{prefix}.id is missing from harness/memory/{file_name}")

    evidence = entry.get("evidence")
    if not coerce_list(evidence):
        errors.append(f"{prefix}.evidence must include at least one source")
    for value in evidence_file_values(evidence):
        candidate = ROOT / path_without_line_suffix(value)
        if not candidate.exists():
            errors.append(f"{prefix}.evidence path does not exist: {value}")

    if entry_id and entry_id not in markdown_ids:
        errors.append(f"{prefix}.id is not present in any memory markdown file")
    return errors


def main():
    if not MEMORY_DIR.exists():
        ok("No harness/memory directory; memory lint skipped.")
        return 0
    if not INDEX_PATH.exists():
        fail("Missing harness/memory/index.yaml")
        return 1

    data = load_yaml(INDEX_PATH)
    entries = data.get("entries") if isinstance(data, dict) else None
    if not isinstance(entries, list):
        fail("harness/memory/index.yaml must contain entries: []")
        return 1

    markdown_ids = markdown_entry_ids()
    indexed_ids = set()
    errors = []
    for index, entry in enumerate(entries):
        entry_id = str(entry.get("id") or "").strip() if isinstance(entry, dict) else ""
        if entry_id:
            if entry_id in indexed_ids:
                errors.append(f"Duplicate memory id in index: {entry_id}")
            indexed_ids.add(entry_id)
        errors.extend(validate_entry(entry, index, markdown_ids))

    for entry_id, paths in markdown_ids.items():
        if entry_id not in indexed_ids:
            locations = ", ".join(rel(path) for path in paths)
            errors.append(f"{entry_id} appears in markdown but not index: {locations}")

    if errors:
        for message in errors:
            fail(message)
        return 1

    ok(f"Memory index is valid ({len(entries)} entries).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
