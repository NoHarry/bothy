# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportUnknownMemberType=false, reportUnknownLambdaType=false, reportUnknownReturnType=false, reportArgumentType=false, reportAny=false, reportExplicitAny=false, reportDeprecated=false, reportUnusedCallResult=false, reportUnusedImport=false
"""Code quality linter for Harness Engineering quality rules."""

import argparse
import fnmatch
import importlib
import json
import re
from datetime import datetime, timezone
from pathlib import Path

from harness_runtime import is_template_repo


ROOT = Path(__file__).resolve().parents[1]
LANGUAGE_BY_SUFFIX = {
    ".py": "python",
    ".js": "javascript",
    ".jsx": "javascript",
    ".mjs": "javascript",
    ".cjs": "javascript",
    ".ts": "typescript",
    ".tsx": "typescript",
    ".java": "java",
    ".go": "go",
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".rs": "rust",
}
ALLOWED_BASELINE_MODES = {"off", "strict", "baseline", "drift_gate"}
DEFAULT_EXCLUDE_GLOBS = [
    ".git/**",
    "**/.git/**",
    "node_modules/**",
    "**/node_modules/**",
    "__pycache__/**",
    "**/__pycache__/**",
    ".ruff_cache/**",
    "**/.ruff_cache/**",
    ".mypy_cache/**",
    "**/.mypy_cache/**",
    ".pytest_cache/**",
    "**/.pytest_cache/**",
    "harness/.local/**",
]


def problem(what, why, fix):
    print(f"[FAIL] {what}")
    print(f"  Why: {why}")
    print(f"  Fix: {fix}")


def emit_issue(severity, location, what, why, fix):
    print(f"[{severity.upper()}] {location}")
    print(f"  What: {what}")
    print(f"  Why: {why}")
    print(f"  Fix: {fix}")


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
    triple("[INFO]", field_path, what, why, how)


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
    parser = argparse.ArgumentParser(
        description="Check configurable code quality rules."
    )
    _ = parser.add_argument(
        "--severity",
        choices=["error", "warning"],
        default="error",
        help="Label emitted issues with the chosen severity.",
    )
    _ = parser.add_argument(
        "--baseline-init",
        action="store_true",
        help="Scan current violations and write baseline.json, then exit.",
    )
    _ = parser.add_argument(
        "--baseline-file",
        default="",
        help=(
            "Path to baseline.json. Defaults to harness/.local/baseline.json "
            "in template repositories and harness/baseline.json in target projects."
        ),
    )
    return parser.parse_args()


def default_baseline_path():
    if is_template_repo(ROOT):
        return ROOT / "harness" / ".local" / "baseline.json"
    return ROOT / "harness" / "baseline.json"


def _is_glob_path(path_str):
    """Return True if path_str contains glob metacharacters (*, ?, [)."""
    return any(c in path_str for c in "*?[")


def platform_roots(config):
    roots = []
    platforms = config.get("platforms") or {}
    if not isinstance(platforms, dict):
        return roots
    for platform in platforms.values():
        if not isinstance(platform, dict):
            continue
        raw_path = str(platform.get("path") or "").replace("\\", "/").strip().rstrip("/")
        if not raw_path or raw_path == ".":
            continue
        roots.append(ROOT / Path(raw_path))
    return roots


def scan_roots(config):
    """
    Collect source root directories from architecture.layers and fallback.

    Glob handling: when architecture.layers uses glob patterns (e.g. "**/ui/**"),
    Path.exists() on the literal "**/ui/**" string always returns False, so those
    layers cannot seed the root list. This function detects that case:

    - Non-glob paths: added to roots only if the directory actually exists.
    - Glob paths  : recorded via has_glob_layer=True flag (not added to roots).

    Fallback logic:
    - If any layer uses glob OR no roots were collected and src/ does not exist,
      return [ROOT] (full repository scan). The caller (iter_source_files) applies
      exclude_globs to skip irrelevant paths.
    - Otherwise return layer roots, configured platform roots, or historical src/.
    """
    roots = []
    seen = set()
    has_glob_layer = False
    architecture = config.get("architecture") or {}
    for item in architecture.get("layers") or []:
        if isinstance(item, dict) and item.get("path"):
            layer_path = str(item.get("path"))
            if _is_glob_path(layer_path):
                has_glob_layer = True
                continue
            candidate = ROOT / Path(layer_path)
            if candidate.exists() and candidate.resolve() not in seen:
                seen.add(candidate.resolve())
                roots.append(candidate)
    for candidate in platform_roots(config):
        if candidate.exists() and candidate.resolve() not in seen:
            seen.add(candidate.resolve())
            roots.append(candidate)
    src_root = ROOT / "src"
    if src_root.exists() and src_root.resolve() not in seen:
        roots.append(src_root)
    if has_glob_layer or not roots:
        return [ROOT]
    return roots


def iter_source_files(config):
    seen = set()
    quality_exclude = (config.get("quality") or {}).get("exclude_paths") or []
    arch_exclude = (config.get("architecture") or {}).get("exclude_paths") or []
    exclude_globs = [*DEFAULT_EXCLUDE_GLOBS, *quality_exclude, *arch_exclude]
    for root in scan_roots(config):
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in LANGUAGE_BY_SUFFIX:
                continue
            resolved = path.resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            rel_path = path.resolve().relative_to(ROOT.resolve())
            rel_str = str(rel_path).replace("\\", "/")
            if any(
                fnmatch.fnmatch(rel_str, p) or fnmatch.fnmatch(str(rel_path), p)
                for p in exclude_globs
            ):
                continue
            yield path


def detect_python_functions(lines):
    functions = []
    index = 0
    while index < len(lines):
        match = re.match(
            r"^(\s*)(?:async\s+def|def)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(", lines[index]
        )
        if not match:
            index += 1
            continue
        indent = len(match.group(1))
        start = index + 1
        end = len(lines)
        probe = index + 1
        while probe < len(lines):
            stripped = lines[probe].strip()
            if stripped:
                current_indent = len(lines[probe]) - len(lines[probe].lstrip(" "))
                if current_indent <= indent and not lines[probe].lstrip().startswith(
                    "#"
                ):
                    end = probe
                    break
            probe += 1
        functions.append((match.group(2), start, end))
        index += 1
    return functions


def detect_brace_functions(lines, suffix):
    patterns = []
    if suffix in {".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx"}:
        patterns = [
            re.compile(
                r"^\s*(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\("
            ),
            re.compile(
                r"^\s*(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*(?:async\s*)?\([^)]*\)\s*=>\s*\{"
            ),
            re.compile(
                r"^\s*(?:public|private|protected|static|readonly|async|\s)+[A-Za-z_$][\w$<>\[\],\s]*\s+([A-Za-z_$][\w$]*)\s*\([^;]*\)\s*\{"
            ),
        ]
    elif suffix == ".java":
        patterns = [
            re.compile(
                r"^\s*(?:public|private|protected|static|final|abstract|synchronized|native|\s)+[A-Za-z0-9_<>\[\],\s?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*\{"
            ),
        ]
    elif suffix == ".go":
        patterns = [
            re.compile(r"^\s*func\s+(?:\([^)]+\)\s*)?([A-Za-z_][A-Za-z0-9_]*)\s*\(")
        ]
    elif suffix in {".kt", ".kts"}:
        patterns = [
            re.compile(
                r"^\s*(?:public|private|protected|internal|open|abstract|final|sealed|companion|\s)*fun\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("
            ),
            re.compile(
                r"^\s*(?:public|private|protected|internal|open|abstract|final|sealed|companion|\s)*[A-Za-z_][A-Za-z0-9_<>\[\],\s?]+\s+([A-Za-z_][A-Za-z0-9_]*)\s*\([^;]*\)\s*\{"
            ),
        ]
    elif suffix == ".rs":
        patterns = [
            re.compile(
                r"^\s*(?:pub\s+)?(?:async\s+)?fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("
            ),
            re.compile(
                r"^\s*(?:pub\s+)?(?:async\s+)?unsafe\s+fn\s+([A-Za-z_][A-Za-z0-9_]*)\s*\("
            ),
        ]

    functions = []
    for index, line in enumerate(lines, start=1):
        name = None
        for pattern in patterns:
            match = pattern.match(line)
            if match:
                name = match.group(1)
                break
        if not name or "{" not in line:
            continue
        depth = line.count("{") - line.count("}")
        end = index
        while end < len(lines) and depth > 0:
            depth += lines[end].count("{") - lines[end].count("}")
            end += 1
        functions.append((name, index, end))
    return functions


def detect_functions(text, suffix):
    lines = text.splitlines()
    if suffix == ".py":
        return detect_python_functions(lines)
    return detect_brace_functions(lines, suffix)


def pattern_applies(rule, language):
    languages = rule.get("languages")
    if not languages:
        return True
    if not isinstance(languages, list):
        return False
    return language.lower() in {str(item).lower() for item in languages}


def line_number(text, offset):
    return text.count("\n", 0, offset) + 1


def load_baseline(baseline_path):
    """Load baseline.json and return (data, error_msg)."""
    if not baseline_path.exists():
        return None, None
    try:
        data = json.loads(baseline_path.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            return None, "baseline.json must contain an object at the top level"
        return data, None
    except json.JSONDecodeError as e:
        return None, f"baseline.json is not valid JSON: {e}"


def in_baseline(baseline_data, file_rel, line, rule):
    """Check if a violation matches an entry in baseline (file exact, line ±3, rule exact)."""
    if not baseline_data or "violations" not in baseline_data:
        return False
    for v in baseline_data["violations"]:
        if v.get("file") != file_rel:
            continue
        if v.get("rule") != rule:
            continue
        baseline_line = v.get("line", 0)
        if abs(baseline_line - line) <= 3:
            return True
    return False


def collect_violations(config, quality):
    """Scan all files and collect violations as a list of dicts."""
    violations = []
    max_file_lines = int(quality.get("max_file_lines", 300))
    max_function_lines = int(quality.get("max_function_lines", 50))
    forbidden_patterns = quality.get("forbidden_patterns") or []
    required_patterns = quality.get("required_patterns") or []

    for path in iter_source_files(config):
        relative = path.resolve().relative_to(ROOT.resolve())
        suffix = path.suffix.lower()
        language = LANGUAGE_BY_SUFFIX[suffix]
        text = path.read_text(encoding="utf-8", errors="ignore")
        lines = text.splitlines()
        rel_str = relative.as_posix()

        if len(lines) > max_file_lines:
            violations.append(
                {
                    "file": rel_str,
                    "line": 1,
                    "rule": "max_file_lines",
                    "message": f"File length is {len(lines)} lines, above the limit of {max_file_lines}.",
                }
            )

        for name, start, end in detect_functions(text, suffix):
            span = end - start + 1
            if span > max_function_lines:
                violations.append(
                    {
                        "file": rel_str,
                        "line": start,
                        "rule": "max_function_lines",
                        "message": f"Function {name} spans {span} lines, above the limit of {max_function_lines}.",
                    }
                )

        if isinstance(forbidden_patterns, list):
            for rule in forbidden_patterns:
                if (
                    not isinstance(rule, dict)
                    or "pattern" not in rule
                    or not pattern_applies(rule, language)
                ):
                    continue
                pattern = str(rule.get("pattern"))
                message = str(rule.get("message") or f"Pattern {pattern} is forbidden.")
                for match in re.finditer(pattern, text, flags=re.MULTILINE):
                    violations.append(
                        {
                            "file": rel_str,
                            "line": line_number(text, match.start()),
                            "rule": "forbidden_pattern",
                            "message": message,
                        }
                    )

        if isinstance(required_patterns, list):
            for rule in required_patterns:
                if (
                    not isinstance(rule, dict)
                    or "pattern" not in rule
                    or not pattern_applies(rule, language)
                ):
                    continue
                pattern = str(rule.get("pattern"))
                message = str(rule.get("message") or f"Pattern {pattern} is required.")
                if not re.search(pattern, text, flags=re.MULTILINE):
                    violations.append(
                        {
                            "file": rel_str,
                            "line": 1,
                            "rule": "required_pattern",
                            "message": message,
                        }
                    )

    return violations


def load_quality_config():
    try:
        config = load_config()
    except FileNotFoundError:
        problem(
            "Harness configuration was not found.",
            "lint_quality.py needs harness.yaml or harness.json at the project root.",
            "Create harness.yaml with a quality section, or generate it from harness.example.yaml during project initialization.",
        )
        return None, None, 1
    except (ValueError, json.JSONDecodeError) as error:
        problem(
            "Harness configuration could not be parsed.",
            str(error),
            "Fix the configuration syntax and run lint_quality.py again.",
        )
        return None, None, 1

    quality = config.get("quality") or {}
    if not isinstance(quality, dict):
        problem(
            "Quality configuration is invalid.",
            "The quality section must be an object.",
            "Rewrite quality in harness.yaml or harness.json as a mapping.",
        )
        return None, None, 1
    return config, quality, 0


def resolve_baseline_path(args):
    baseline_path = Path(args.baseline_file) if args.baseline_file else default_baseline_path()
    return baseline_path if baseline_path.is_absolute() else ROOT / baseline_path


def baseline_project_name(config):
    project = config.get("project")
    if isinstance(project, dict):
        return project.get("name", "unknown")
    return "unknown"


def run_baseline_init(config, quality, baseline_path):
    violations = collect_violations(config, quality)
    baseline_data = {
        "version": "1.0",
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "project": baseline_project_name(config),
        "violations": violations,
    }
    try:
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(
            json.dumps(baseline_data, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
    except OSError as e:
        err(
            str(baseline_path),
            f"Failed to write baseline file: {e}",
            "Could not create or write to the baseline file.",
            "Check directory permissions and run again.",
        )
        return 1
    print(f"[OK] Wrote {len(violations)} violations to {baseline_path}")
    return 0


def validate_baseline_mode(baseline_mode):
    if baseline_mode in ALLOWED_BASELINE_MODES:
        return 0
    err(
        "quality.baseline_mode",
        f"Unknown baseline_mode: {baseline_mode}",
        "Unknown modes silently disable quality gates.",
        f"Use one of: {', '.join(sorted(ALLOWED_BASELINE_MODES))}.",
    )
    return 1


def load_baseline_for_mode(baseline_mode, baseline_path):
    if baseline_mode == "off":
        return None, 0
    baseline_data, baseline_error = load_baseline(baseline_path)
    if baseline_error:
        err(
            str(baseline_path),
            f"Baseline file error: {baseline_error}",
            "The baseline file could not be loaded.",
            "Fix the baseline JSON or run --baseline-init to regenerate it.",
        )
        return None, 1
    if baseline_data is None:
        warn(
            str(baseline_path),
            "Baseline file not found but baseline_mode is not 'off'.",
            "Running without baseline filtering.",
            "Run --baseline-init to generate baseline.json.",
        )
    return baseline_data, 0


def violation_location(violation):
    line = int(violation.get("line", 1))
    if line <= 1 and violation.get("rule") in {"max_file_lines", "required_pattern"}:
        return violation["file"]
    return f"{violation['file']}:{line}"


def violation_guidance(rule):
    if rule == "max_file_lines":
        return (
            "Oversized files are harder to review, test, and refactor safely.",
            "Split the file into smaller modules with clearer responsibilities.",
        )
    if rule == "max_function_lines":
        return (
            "Long functions usually mix responsibilities and slow down review.",
            "Extract smaller helpers or move logic into dedicated collaborators.",
        )
    if rule == "forbidden_pattern":
        return (
            "The configured rule marks this construct as disallowed.",
            "Replace it with the approved project alternative for this language.",
        )
    return (
        "The configured rule requires this safeguard or structure in matching files.",
        "Add the missing pattern or narrow the rule scope in harness.yaml or harness.json.",
    )


def should_emit_violation(violation, baseline_mode, baseline_data):
    if baseline_mode in {"off", "strict"}:
        return True
    return not in_baseline(
        baseline_data,
        violation["file"],
        int(violation.get("line", 1)),
        violation["rule"],
    )


def emit_quality_violation(severity, violation):
    why, fix = violation_guidance(violation["rule"])
    emit_issue(severity, violation_location(violation), violation["message"], why, fix)


def current_violation_matches(baseline_item, current_violation):
    if baseline_item.get("file") != current_violation.get("file"):
        return False
    if baseline_item.get("rule") != current_violation.get("rule"):
        return False
    baseline_line = int(baseline_item.get("line", 1) or 1)
    current_line = int(current_violation.get("line", 1) or 1)
    return abs(baseline_line - current_line) <= 3


def fixed_baseline_items(baseline_data, current_violations):
    if not baseline_data or not isinstance(baseline_data.get("violations"), list):
        return []
    fixed = []
    for item in baseline_data["violations"]:
        if not isinstance(item, dict):
            continue
        if not any(current_violation_matches(item, v) for v in current_violations):
            fixed.append(item)
    return fixed


def emit_fixed_baseline_items(baseline_data, current_violations):
    for item in fixed_baseline_items(baseline_data, current_violations):
        info(
            f"{item.get('file')}:{item.get('line', 1)}",
            f"Rule '{item.get('rule')}' is now satisfied (was in baseline).",
            "This violation was in baseline but no longer exists; this is positive drift.",
            "Consider running --baseline-init to update baseline.json to reflect improvements.",
        )


def run_quality_check(args, config, quality, baseline_mode, baseline_data):
    current_violations = collect_violations(config, quality)
    issues = 0
    for violation in current_violations:
        if should_emit_violation(violation, baseline_mode, baseline_data):
            issues += 1
            emit_quality_violation(args.severity, violation)
    if baseline_mode == "drift_gate":
        emit_fixed_baseline_items(baseline_data, current_violations)
    if issues:
        return 1
    print("[OK] No quality issues found")
    return 0


def main():
    args = parse_args()
    config, quality, exit_code = load_quality_config()
    if exit_code:
        return exit_code

    baseline_path = resolve_baseline_path(args)
    if args.baseline_init:
        return run_baseline_init(config, quality, baseline_path)

    baseline_mode = str(quality.get("baseline_mode", "off"))
    if validate_baseline_mode(baseline_mode):
        return 1
    baseline_data, exit_code = load_baseline_for_mode(baseline_mode, baseline_path)
    if exit_code:
        return exit_code
    return run_quality_check(args, config, quality, baseline_mode, baseline_data)


if __name__ == "__main__":
    raise SystemExit(main())
