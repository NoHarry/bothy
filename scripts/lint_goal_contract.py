# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Validate feature-scoped Harness goal contracts."""

import argparse
import re
from pathlib import Path

from harness_runtime import ROOT


DEFAULT_CONTRACT_GLOB = "doc/features/**/outputs/goal-contract.md"
DEFAULT_REQUIRED_SECTIONS = [
    "唯一目标",
    "终态判定",
    "结果计数",
    "固定执行循环",
    "硬性禁止",
    "受阻处理",
    "每轮自检",
    "生命周期",
]
VALID_PROJECT_KINDS = {"greenfield", "migration", "maintenance", "refactor", "research"}


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


def heading_matches(heading, section_name):
    text = re.sub(r"^\d+(?:\.\d+)*\s*[.)]?\s*", "", heading.strip())
    return section_name in text


def iter_headings(text):
    pattern = re.compile(r"(?m)^(#{1,6})\s+(.+?)\s*$")
    for match in pattern.finditer(text):
        yield {
            "level": len(match.group(1)),
            "title": match.group(2).strip(),
            "start": match.start(),
            "content_start": match.end(),
        }


def has_section(text, section_name):
    return any(heading_matches(item["title"], section_name) for item in iter_headings(text))


def section_text(text, section_name):
    headings = list(iter_headings(text))
    for index, item in enumerate(headings):
        if not heading_matches(item["title"], section_name):
            continue
        end = len(text)
        for next_item in headings[index + 1 :]:
            if next_item["level"] <= item["level"]:
                end = next_item["start"]
                break
        return text[item["content_start"] : end]
    return ""


def has_markdown_checklist(text):
    return bool(re.search(r"(?m)^\s*[-*]\s+\[[ xX]\]\s+\S", text))


def has_counter_table(text):
    table_lines = [line for line in text.splitlines() if line.strip().startswith("|")]
    if len(table_lines) < 3:
        return False
    joined = "\n".join(table_lines).lower()
    has_counter_col = "counter" in joined or "计数" in joined
    has_direction_col = "direction" in joined or "推进方向" in joined
    has_direction_value = any(token in joined for token in ("up", "down", "上升", "下降"))
    return has_counter_col and has_direction_col and has_direction_value


def required_sections():
    return DEFAULT_REQUIRED_SECTIONS


def extract_project_kind(text):
    match = re.search(r"(?im)^\s*[-*]?\s*project kind\s*:\s*(.+?)\s*$", text)
    if not match:
        return ""
    raw = match.group(1).strip().strip("`'\"")
    raw = re.sub(r"\s+#.*$", "", raw).strip().strip("`'\"")
    return raw


def validate_contract_text(root, path, text):
    errors = []
    kind = extract_project_kind(text)
    if kind and not is_placeholder(kind) and kind not in VALID_PROJECT_KINDS:
        errors.append(
            f"{rel(root, path)} project kind must be one of greenfield, migration, maintenance, refactor, research"
        )

    for section in required_sections():
        if not has_section(text, section):
            errors.append(f"{rel(root, path)} missing section '{section}'")

    terminal = section_text(text, "终态判定")
    if terminal and not has_markdown_checklist(terminal):
        errors.append(f"{rel(root, path)} section '终态判定' must contain markdown checklist items")

    counters = section_text(text, "结果计数")
    if counters:
        if "BLACKBOARD.md" not in counters:
            errors.append(f"{rel(root, path)} section '结果计数' must reference BLACKBOARD.md")
        if not has_counter_table(counters):
            errors.append(
                f"{rel(root, path)} section '结果计数' must contain a counter table with direction values"
            )

    hard_bans = section_text(text, "硬性禁止").lower()
    if hard_bans and not any(
        token in hard_bans
        for token in ("planner", "auditor", "evidence", "scaffold", "证据", "脚手架")
    ):
        errors.append(
            f"{rel(root, path)} section '硬性禁止' must ban meta/scaffold/evidence-only progress"
        )

    blocked = section_text(text, "受阻处理").lower()
    if blocked and not ("consecutive" in blocked or "连续" in blocked):
        errors.append(f"{rel(root, path)} section '受阻处理' must define a no-progress stop rule")

    lifecycle = section_text(text, "生命周期").lower()
    if lifecycle:
        if not any(token in lifecycle for token in ("owner", "负责人", "decision", "决策")):
            errors.append(
                f"{rel(root, path)} section '生命周期' must require owner/decision evidence for goal changes"
            )
        if not any(token in lifecycle for token in ("archive", "归档", "changelog", "变更")):
            errors.append(
                f"{rel(root, path)} section '生命周期' must require archiving or changelogging the previous phase"
            )
        if not any(token in lifecycle for token in ("phase", "阶段")):
            errors.append(
                f"{rel(root, path)} section '生命周期' must describe phase transition triggers"
            )

    return errors


def normalize_requested_file(root, file_name):
    candidate = Path(file_name)
    path = candidate if candidate.is_absolute() else root / candidate
    path = path.resolve()
    try:
        path.relative_to(root.resolve())
    except ValueError:
        return path, f"{file_name} must stay inside the repository"
    return path, ""


def discover_contracts(root):
    return sorted(path for path in root.glob(DEFAULT_CONTRACT_GLOB) if path.is_file())


def validate(root, files=None, require=False):
    errors = []
    if files:
        paths = []
        for file_name in files:
            path, error = normalize_requested_file(root, file_name)
            if error:
                errors.append(error)
                continue
            paths.append(path)
    else:
        paths = discover_contracts(root)

    if require and not paths:
        errors.append(f"No goal contracts found at {DEFAULT_CONTRACT_GLOB}")

    for path in paths:
        if not path.exists():
            errors.append(f"{rel(root, path)} does not exist")
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        errors.extend(validate_contract_text(root, path, text))
    return errors


def main():
    parser = argparse.ArgumentParser(description="Validate Harness goal contract.")
    _ = parser.add_argument("--root", default=str(ROOT))
    _ = parser.add_argument(
        "--file",
        action="append",
        default=[],
        help="Repository-relative goal contract path to validate. May be repeated.",
    )
    _ = parser.add_argument(
        "--require",
        action="store_true",
        help="Fail when no feature-scoped goal contracts are found.",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    errors = validate(root, files=args.file, require=args.require)
    if errors:
        for message in errors:
            fail(message)
        return 1
    if args.file or discover_contracts(root):
        ok("Goal contract is valid.")
    else:
        ok("No feature goal contracts found; skipping goal contract lint.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
