# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Generate a read-only Harness dream report from recent project records."""

import argparse
import json
import re
from collections import Counter
from datetime import datetime
from pathlib import Path

from harness_runtime import ROOT, display_path, dream_report_path, load_yaml


SIGNAL_PATTERNS = {
    "failure": re.compile(r"\b(fail|failed|failure|error|exception|timeout)\b", re.I),
    "skip": re.compile(r"\b(skip|skipped|disabled|bypass|waive)\b", re.I),
    "owner_action": re.compile(r"\b(owner-action|owner action|review_required|hardening[-_ ]debt)\b", re.I),
    "validation": re.compile(r"\b(validate|validation|lint|test|build|check|pass)\b", re.I),
}
RUNTIME_DIR = ROOT / "harness" / ".local" / "agent-runtime" / "runs"
RUNTIME_SIGNAL_FILES = {
    "events.jsonl",
    "validation.json",
    "eval.json",
    "result.md",
    "handoff.md",
    "memory-candidates.yaml",
    "eval-candidates.yaml",
}


def read_text(path):
    try:
        return path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def iter_markdown(root, rel_dir, limit=None):
    base = root / rel_dir
    if not base.exists():
        return []
    files = [
        path
        for path in base.rglob("*.md")
        if path.is_file() and "archive" not in {part.lower() for part in path.parts}
    ]
    files.sort(key=lambda path: path.stat().st_mtime, reverse=True)
    return files[:limit] if limit else files


def recent_trace_files(limit):
    files = [path for path in iter_markdown(ROOT, "harness/trace") if path.name.lower() != "readme.md"]
    return files[:limit] if limit else files


def memory_files():
    return [
        path
        for path in iter_markdown(ROOT, "harness/memory")
        if path.name.lower() != "readme.md"
    ]


def recent_runtime_files(limit):
    if not RUNTIME_DIR.exists():
        return []
    runs = [path for path in RUNTIME_DIR.iterdir() if path.is_dir()]
    runs.sort(key=lambda path: path.stat().st_mtime, reverse=True)
    selected = runs[:limit] if limit else runs
    files = []
    for run_dir in selected:
        for name in RUNTIME_SIGNAL_FILES:
            path = run_dir / name
            if path.exists():
                files.append(path)
        blockers = run_dir / "blockers"
        if blockers.exists():
            files.extend(sorted(blockers.glob("*.yaml")))
    return files


def count_lines(text):
    return len(text.splitlines())


def extract_signals(files, max_per_file=6):
    signals = []
    for path in files:
        rel = display_path(ROOT, path)
        hits = []
        for number, line in enumerate(read_text(path).splitlines(), start=1):
            stripped = line.strip()
            if not stripped:
                continue
            for name, pattern in SIGNAL_PATTERNS.items():
                if pattern.search(stripped):
                    hits.append(
                        {
                            "kind": name,
                            "file": rel,
                            "line": number,
                            "text": stripped[:180],
                        }
                    )
                    break
            if len(hits) >= max_per_file:
                break
        signals.extend(hits)
    return signals


def memory_cleanup_suggestions(files):
    suggestions = []
    for path in files:
        text = read_text(path)
        lines = count_lines(text)
        headings = [
            re.sub(r"\s+", " ", line.strip("# ").strip()).lower()
            for line in text.splitlines()
            if line.startswith("### ")
        ]
        duplicates = [title for title, count in Counter(headings).items() if count > 1]
        if lines > 500:
            suggestions.append(
                {
                    "finding": f"`{display_path(ROOT, path)}` has {lines} lines.",
                    "evidence": f"{display_path(ROOT, path)}:1",
                    "confidence": "high",
                    "recommended_next_skill": "memory-distill",
                }
            )
        if duplicates:
            suggestions.append(
                {
                    "finding": "`{}` has repeated headings: {}".format(
                        display_path(ROOT, path), ", ".join(sorted(duplicates)[:5])
                    ),
                    "evidence": f"{display_path(ROOT, path)}:1",
                    "confidence": "medium",
                    "recommended_next_skill": "memory-distill",
                }
            )
    return suggestions


def confidence_for_signal(signal):
    kind = signal.get("kind")
    text = str(signal.get("text") or "").lower()
    if kind in {"failure", "skip"} and any(
        token in text for token in ("error", "failed", "timeout", "disabled", "skip")
    ):
        return "high"
    if kind == "owner_action":
        return "high"
    if kind == "validation":
        return "medium"
    return "low"


def recommended_skill_for_signal(signal):
    kind = signal.get("kind")
    text = str(signal.get("text") or "").lower()
    if kind == "owner_action":
        return "harness-executor"
    if kind == "skip":
        return "harness-executor"
    if kind == "failure":
        if any(token in text for token in ("test", "coverage", "assert")):
            return "code-to-test"
        return "harness-executor"
    if kind == "validation":
        return "workspace-doctor"
    return "harness-dream"


def structured_findings(signals, memory_suggestions, missing_validation):
    findings = []
    for item in memory_suggestions:
        findings.append(
            {
                "type": "memory_cleanup",
                "finding": item["finding"],
                "evidence": item["evidence"],
                "confidence": item["confidence"],
                "recommended_next_skill": item["recommended_next_skill"],
            }
        )
    for signal in signals:
        if signal["kind"] not in {"failure", "skip", "owner_action"}:
            continue
        findings.append(
            {
                "type": signal["kind"],
                "finding": signal["text"],
                "evidence": f"{signal['file']}:{signal['line']}",
                "confidence": confidence_for_signal(signal),
                "recommended_next_skill": recommended_skill_for_signal(signal),
            }
        )
    for rel in missing_validation:
        findings.append(
            {
                "type": "missing_validation",
                "finding": "Trace does not mention validation/build/test/lint evidence.",
                "evidence": f"{rel}:1",
                "confidence": "medium",
                "recommended_next_skill": "harness-executor",
            }
        )
    return findings[:40]


def hardening_debt_items():
    data = {}
    for name in ("harness.yaml", "harness.example.yaml"):
        path = ROOT / name
        if path.exists():
            data = load_yaml(path)
            break
    debt = data.get("hardening_debt") if isinstance(data, dict) else []
    if isinstance(debt, list):
        return debt
    if isinstance(debt, dict):
        items = []
        for group, values in debt.items():
            if isinstance(values, list):
                for item in values:
                    if isinstance(item, dict):
                        merged = {"group": group}
                        merged.update(item)
                        items.append(merged)
            elif values:
                items.append({"group": group, "item": str(values)})
        return items
    return []


def missing_validation_candidates(trace_files):
    candidates = []
    for path in trace_files:
        text = read_text(path)
        if not SIGNAL_PATTERNS["validation"].search(text):
            candidates.append(display_path(ROOT, path))
    return candidates[:20]


def report_header(trace_files, memory, runtime_files, debt, counts):
    generated = datetime.now().isoformat(timespec="seconds")
    lines = [
        "# Harness Dream Report",
        "",
        f"- Generated: {generated}",
        f"- Trace files scanned: {len(trace_files)}",
        f"- Memory files scanned: {len(memory)}",
        f"- Runtime files scanned: {len(runtime_files)}",
        f"- Hardening debt items found: {len(debt)}",
        "",
        "## Signal Summary",
        "",
        "| Signal | Count |",
        "|---|---:|",
    ]
    for key in ("failure", "skip", "owner_action", "validation"):
        lines.append(f"| {key} | {counts.get(key, 0)} |")
    return lines


def structured_findings_section(findings):
    lines = [
        "",
        "## Structured Findings",
        "",
        "| Type | Finding | Evidence | Confidence | Recommended Next Skill |",
        "|---|---|---|---|---|",
    ]
    if not findings:
        lines.append("| none | No actionable finding detected. | - | - | - |")
        return lines
    for finding in findings:
        text = str(finding["finding"]).replace("|", "\\|")
        lines.append(
            "| {type} | {finding} | `{evidence}` | {confidence} | {skill} |".format(
                type=finding["type"],
                finding=text[:180],
                evidence=finding["evidence"],
                confidence=finding["confidence"],
                skill=finding["recommended_next_skill"],
            )
        )
    return lines


def notable_signals_section(signals):
    lines = ["", "## Notable Signals", ""]
    if not signals:
        lines.append("- No notable signals found in the selected trace files.")
        return lines
    for signal in signals[:40]:
        lines.append(
            f"- {signal['kind']}: `{signal['file']}:{signal['line']}` - {signal['text']}"
        )
    return lines


def memory_cleanup_section(memory_suggestions):
    lines = ["", "## Memory Cleanup Suggestions", ""]
    if not memory_suggestions:
        lines.append("- No memory cleanup threshold was crossed.")
        return lines
    for item in memory_suggestions:
        lines.append(
            "- {finding} Evidence: `{evidence}`. Confidence: {confidence}. Next: `{skill}`.".format(
                finding=item["finding"],
                evidence=item["evidence"],
                confidence=item["confidence"],
                skill=item["recommended_next_skill"],
            )
        )
    return lines


def memory_candidates_section(findings):
    lines = ["", "## Memory Candidates", ""]
    candidate_lines = [
        finding
        for finding in findings
        if finding["type"] in {"failure", "memory_cleanup", "missing_validation"}
    ]
    if not candidate_lines:
        lines.append("- No memory candidates generated.")
        return lines
    for finding in candidate_lines[:20]:
        lines.append(
            "- Candidate: {finding} | evidence: `{evidence}` | confidence: {confidence} | next: `{skill}`".format(
                finding=str(finding["finding"])[:180],
                evidence=finding["evidence"],
                confidence=finding["confidence"],
                skill=finding["recommended_next_skill"],
            )
        )
    return lines


def eval_candidates_section(runtime_files):
    lines = ["", "## Eval Candidates", ""]
    eval_candidate_files = [
        display_path(ROOT, path)
        for path in runtime_files
        if path.name == "eval-candidates.yaml" and "candidates: []" not in read_text(path)
    ]
    if not eval_candidate_files:
        lines.append("- No runtime eval candidates found.")
        return lines
    for rel in eval_candidate_files[:20]:
        lines.append(f"- Review runtime eval candidate file `{rel}` before promoting anything into `harness/evals/`.")
    return lines


def hardening_candidates_section(signals):
    lines = ["", "## Hardening Candidates", ""]
    failure_like = [signal for signal in signals if signal["kind"] in {"failure", "skip"}]
    if not failure_like:
        lines.append("- No failure or skip signal found in the selected trace files.")
        return lines
    for signal in failure_like[:20]:
        lines.append(
            f"- Review `{signal['file']}:{signal['line']}` and decide whether it needs a gate, test, or owner action."
        )
    return lines


def owner_actions_section(debt):
    lines = ["", "## Owner Actions", ""]
    if not debt:
        lines.append("- No hardening_debt items found in harness config.")
        return lines
    for item in debt[:30]:
        if isinstance(item, dict):
            label = item.get("id") or item.get("item") or item.get("reason") or item
            owner = item.get("owner", "owner TBD")
            status = item.get("status", "OPEN")
            lines.append(f"- {label} | owner: {owner} | status: {status}")
        else:
            lines.append(f"- {item}")
    return lines


def missing_validation_section(trace_files, missing_validation):
    lines = ["", "## Missing Validation Evidence", ""]
    if not trace_files:
        lines.append("- No trace files were selected.")
    elif missing_validation:
        for rel in missing_validation:
            lines.append(f"- `{rel}` does not mention validation/build/test/lint evidence.")
    else:
        lines.append("- Selected trace files contain validation-like evidence.")
    return lines


def boundaries_section():
    return [
        "",
        "## Boundaries",
        "",
        "- This report is read-only.",
        "- It must not directly modify source, memory, evals, prompts, or policy.",
        "- Candidate memory must go through `memory-distill` or a normal Harness task before it becomes durable memory.",
        "- Runtime eval candidates must go through owner review before entering `harness/evals/`.",
        "- Convert recommendations into owner actions or normal Harness tasks before applying changes.",
    ]


def render_report(trace_files, memory, runtime_files, signals, debt, missing_validation):
    counts = Counter(signal["kind"] for signal in signals)
    memory_suggestions = memory_cleanup_suggestions(memory)
    findings = structured_findings(signals, memory_suggestions, missing_validation)
    lines = report_header(trace_files, memory, runtime_files, debt, counts)
    lines.extend(
        structured_findings_section(findings)
        + notable_signals_section(signals)
        + memory_cleanup_section(memory_suggestions)
        + memory_candidates_section(findings)
        + eval_candidates_section(runtime_files)
        + hardening_candidates_section(signals)
        + owner_actions_section(debt)
        + missing_validation_section(trace_files, missing_validation)
        + boundaries_section()
    )
    return "\n".join(lines) + "\n"


def parse_args():
    parser = argparse.ArgumentParser(description="Generate a read-only Harness dream report.")
    _ = parser.add_argument("--limit", type=int, default=20, help="Recent trace file limit.")
    _ = parser.add_argument("--write-report", action="store_true", help="Write report to runtime path.")
    _ = parser.add_argument("--json", action="store_true", help="Print machine-readable summary.")
    return parser.parse_args()


def main():
    args = parse_args()
    trace_files = recent_trace_files(args.limit)
    memory = memory_files()
    runtime_files = recent_runtime_files(args.limit)
    signals = extract_signals(trace_files) + extract_signals(runtime_files)
    debt = hardening_debt_items()
    missing = missing_validation_candidates(trace_files)
    report_text = render_report(trace_files, memory, runtime_files, signals, debt, missing)
    report_path = None
    if args.write_report:
        filename = f"{datetime.now().date().isoformat()}-harness-dream.md"
        report_path = dream_report_path(ROOT, filename)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report_text, encoding="utf-8")

    if args.json:
        print(
            json.dumps(
                {
                    "trace_files": len(trace_files),
                    "memory_files": len(memory),
                    "runtime_files": len(runtime_files),
                    "signals": Counter(signal["kind"] for signal in signals),
                    "hardening_debt": len(debt),
                    "missing_validation": len(missing),
                    "report": display_path(ROOT, report_path) if report_path else "",
                },
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        if report_path:
            print(f"[REPORT] {display_path(ROOT, report_path)}")
        print(report_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
