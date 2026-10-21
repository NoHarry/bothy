# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportUnknownMemberType=false, reportUnknownLambdaType=false, reportUnknownReturnType=false, reportArgumentType=false, reportAny=false, reportExplicitAny=false, reportDeprecated=false, reportUnusedCallResult=false, reportUnusedImport=false
"""Architecture dependency linter for the Harness Engineering framework."""

import argparse
import fnmatch
import importlib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
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
}


# ----------------------------------------------------------------------
# Triple-message format (matches validate.py)
# ----------------------------------------------------------------------
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


def problem(what, why, fix):
    print(f"[FAIL] {what}")
    print(f"  Why: {why}")
    print(f"  Fix: {fix}")


# ----------------------------------------------------------------------
# Config loading
# ----------------------------------------------------------------------
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
        description="Check architecture dependency violations."
    )
    _ = parser.add_argument(
        "--fix-suggestions",
        action="store_true",
        help="Show extra remediation guidance.",
    )
    _ = parser.add_argument(
        "--no-gradle",
        action="store_true",
        help="Skip Gradle module-graph scanning (pure file-scan mode).",
    )
    _ = parser.add_argument(
        "--warn-on-unresolved",
        action="store_true",
        help="Downgrade unresolved imports from ERROR to WARN instead of failing.",
    )
    return parser.parse_args()


# ----------------------------------------------------------------------
# Layer utilities
# ----------------------------------------------------------------------
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
                    "import_prefix": str(item.get("import_prefix") or "")
                    .replace("\\", ".")
                    .strip()
                    .rstrip("."),
                }
            )
    return sorted(layers, key=lambda item: len(item["path"]), reverse=True)


def get_isolation_rules(config):
    architecture = config.get("architecture") or {}
    raw_rules = architecture.get("isolation") or []
    rules = []
    if not isinstance(raw_rules, list):
        return rules
    for index, item in enumerate(raw_rules):
        if not isinstance(item, dict):
            continue
        prefix = str(item.get("import_prefix") or "").replace("\\", ".").strip().rstrip(".")
        raw_paths = item.get("allowed_paths")
        allowed_paths = raw_paths if isinstance(raw_paths, list) else []
        rules.append(
            {
                "index": index,
                "import_prefix": prefix,
                "allowed_paths": [str(path).replace("\\", "/").strip() for path in allowed_paths],
                "note": str(item.get("note") or "").strip(),
            }
        )
    return rules


def reference_matches_prefix(reference, prefix):
    if not prefix:
        return False
    normalized_ref = reference.replace("\\", ".").replace("/", ".").strip().rstrip(".")
    normalized_prefix = prefix.replace("\\", ".").replace("/", ".").strip().rstrip(".")
    return normalized_ref == normalized_prefix or normalized_ref.startswith(
        normalized_prefix + "."
    )


def path_allowed_by_isolation(relative_path, allowed_paths):
    if not allowed_paths:
        return False
    rel = relative_path.as_posix()
    for pattern in allowed_paths:
        normalized = pattern.strip().replace("\\", "/")
        if not normalized:
            continue
        if fnmatch.fnmatch(rel, normalized):
            return True
        if normalized.endswith("/**"):
            base = normalized[:-3].rstrip("/")
            if rel == base or rel.startswith(base + "/"):
                return True
        if rel == normalized.rstrip("/") or rel.startswith(normalized.rstrip("/") + "/"):
            return True
    return False


def validate_isolation_config(rules):
    errors = []
    for rule in rules:
        path = f"architecture.isolation[{rule['index']}]"
        if not rule["import_prefix"]:
            errors.append((path, "missing import_prefix"))
        for allowed_path in rule["allowed_paths"]:
            if allowed_path.startswith(("/", "//")) or re.match(
                r"^[A-Za-z]:[\\/]", allowed_path
            ):
                errors.append((path, f"allowed_paths entry must be repository-relative: {allowed_path}"))
    return errors


def matches_layer_path(path_text, layer_path):
    normalized_path = path_text.lower().strip("/")
    normalized_layer = layer_path.lower().strip("/")
    return normalized_path == normalized_layer or normalized_path.startswith(
        normalized_layer + "/"
    )


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


def scan_roots(layers, config):
    roots = []
    seen = set()
    for layer in layers:
        candidate = ROOT / Path(layer["path"])
        if candidate.exists():
            resolved = candidate.resolve()
            if resolved not in seen:
                seen.add(resolved)
                roots.append(candidate)
    for candidate in platform_roots(config):
        if candidate.exists():
            resolved = candidate.resolve()
            if resolved not in seen:
                seen.add(resolved)
                roots.append(candidate)
    src_root = ROOT / "src"
    if src_root.exists() and src_root.resolve() not in seen:
        roots.append(src_root)
    return roots


def iter_source_files(layers, config):
    """Iterate source files, respecting exclude_paths globs."""
    exclude_globs = []
    arch = config.get("architecture") or {}
    exclude_globs = arch.get("exclude_paths") or []

    seen = set()
    for root in scan_roots(layers, config):
        for path in root.rglob("*"):
            if not path.is_file() or path.suffix.lower() not in SOURCE_SUFFIXES:
                continue
            resolved = path.resolve()
            if resolved in seen:
                continue

            # Apply exclude_paths glob filtering
            rel = resolved.relative_to(ROOT.resolve()).as_posix()
            rel_slash = rel.replace("\\", "/")
            if any(
                fnmatch.fnmatch(rel, g) or fnmatch.fnmatch(rel_slash, g)
                for g in exclude_globs
            ):
                continue

            seen.add(resolved)
            yield path


def layer_for_path(path, layers):
    text = path.as_posix().lower()
    for layer in layers:
        if matches_layer_path(text, layer["path"]):
            return layer
    return None


def layer_aliases(layer):
    path = layer["path"].lower().strip("/")
    aliases = {
        path,
        path.replace("/", "."),
        Path(path).name.lower(),
        layer["name"].lower(),
    }
    import_prefix = str(layer.get("import_prefix") or "").lower().strip().rstrip(".")
    if import_prefix:
        aliases.add(import_prefix)
    return aliases


def layer_from_reference(reference, layers):
    text = reference.lower().replace("\\", "/").strip("./")
    dotted = text.replace("/", ".")
    best = None
    best_size = -1
    for layer in layers:
        for alias in layer_aliases(layer):
            if not alias:
                continue
            dotted_alias = alias.replace("/", ".")
            if (
                text == alias
                or dotted == dotted_alias
                or text.startswith(alias + "/")
                or dotted.startswith(dotted_alias + ".")
            ):
                if len(alias) > best_size:
                    best = layer
                    best_size = len(alias)
    return best


def normalize_reference(reference, source_path):
    text = reference.replace("\\", "/").strip()
    if not text or not text.startswith("."):
        return text
    candidate = (ROOT / source_path.parent / text).resolve()
    try:
        return candidate.relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return text


# ----------------------------------------------------------------------
# Import extraction
# ----------------------------------------------------------------------
def extract_imports(text, suffix):
    imports = []
    go_block = False
    for line_number, line in enumerate(text.splitlines(), start=1):
        if suffix == ".py":
            match = re.match(r"^\s*from\s+([A-Za-z0-9_\.]+)\s+import\s+", line)
            if match:
                imports.append((line_number, match.group(1)))
            match = re.match(r"^\s*import\s+(.+)$", line)
            if match:
                for name in match.group(1).split(","):
                    imports.append((line_number, name.strip().split(" as ")[0].strip()))
            continue
        if suffix in {".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx"}:
            for match in re.finditer(
                r"import\s+(?:.+?\s+from\s+)?['\"]([^'\"]+)['\"]", line
            ):
                imports.append((line_number, match.group(1)))
            for match in re.finditer(r"require\(\s*['\"]([^'\"]+)['\"]\s*\)", line):
                imports.append((line_number, match.group(1)))
            continue
        if suffix == ".java":
            match = re.match(r"^\s*import\s+([A-Za-z0-9_\.]+)\s*;", line)
            if match:
                imports.append((line_number, match.group(1)))
            continue
        if suffix == ".go":
            if re.match(r"^\s*import\s*\(\s*$", line):
                go_block = True
                continue
            if go_block and re.match(r"^\s*\)\s*$", line):
                go_block = False
                continue
            match = re.match(r"^\s*import\s+\"([^\"]+)\"", line)
            if match:
                imports.append((line_number, match.group(1)))
            if go_block:
                for inner in re.finditer(r'"([^\"]+)"', line):
                    imports.append((line_number, inner.group(1)))
            continue
        # Kotlin (.kt, .kts)
        if suffix in {".kt", ".kts"}:
            # Matches: import com.example.MyClass
            #          import com.example.MyClass as Baz
            #          import com.example.MyClass.*  (wildcard)
            # Kotlin has no semicolon, may have 'as' alias, may end with .*
            match = re.match(
                r"^\s*import\s+([A-Za-z0-9_\.]+)(?:\s+as\s+\w+)?(?:\s*\.\*)?\s*$",
                line,
            )
            if match:
                raw = match.group(1)
                # Wildcard import — store prefix + .* for wildcard matching in layer resolution
                if ".*" in line:
                    imports.append((line_number, raw + ".*"))
                else:
                    imports.append((line_number, raw))
            continue
    return [(ln, ref) for ln, ref in imports if ref]


# ----------------------------------------------------------------------
# Gradle module scanning — 8 regex patterns from detail-design.md lines 49-59 (SPEC-B2)
# ----------------------------------------------------------------------
# Pattern 1: settings include
_RE_SETTINGS_INCLUDE = re.compile(r'include\s*\(\s*["\']:?([^"\)\s]+)["\']?\s*\)')
# Pattern 2: settings include kts (multi-line)
_RE_SETTINGS_INCLUDE_KTS = re.compile(r"include\s*\(([^)]+)\)")
# Pattern 3: project dependency
_RE_PROJECT_DEP = re.compile(
    r'implementation\s+project\s*\(\s*["\']:?([^"\)\s]+)["\']?\s*\)'
)
# Pattern 4: project dependency kts
_RE_PROJECT_DEP_KTS = re.compile(r'project\s*\(\s*["\']:?([^"\)\s]+)["\']?\s*\)')
# Pattern 5: api dependency
_RE_API_DEP = re.compile(r'api\s+project\s*\(\s*["\']:?([^"\)\s]+)["\']?\s*\)')
# Pattern 6: compileOnly dependency
_RE_COMPILEONLY_DEP = re.compile(
    r'compileOnly\s+project\s*\(\s*["\']:?([^"\)\s]+)["\']?\s*\)'
)
# Pattern 7: comment line skip
_RE_COMMENT = re.compile(r"^\s*//")
# Pattern 8: string interpolation skip
_RE_INTERPOLATION = re.compile(r"\$\{")


def _normalize_gradle_name(s):
    """Strip quotes and leading colon from a Gradle module name."""
    return s.strip().strip("'\"").lstrip(":")


def scan_gradle_modules(root):
    """Scan Gradle settings and build files to build a module dependency graph.

    Returns:
        dict: {module_name: set(dependency_module_names)}
    """
    graph = {}

    # Find settings.gradle or settings.gradle.kts
    settings_path = None
    for name in ("settings.gradle", "settings.gradle.kts", "settings.kts"):
        p = root / name
        if p.exists():
            settings_path = p
            break

    if not settings_path:
        return graph

    settings_text = settings_path.read_text(encoding="utf-8", errors="ignore")

    # Collect all module names from settings file
    module_names = set()
    for line in settings_text.splitlines():
        stripped = line.strip()
        if _RE_COMMENT.match(stripped) or _RE_INTERPOLATION.search(stripped):
            continue
        m = _RE_SETTINGS_INCLUDE.search(stripped)
        if m:
            module_names.add(_normalize_gradle_name(m.group(1)))
            continue
        m = _RE_SETTINGS_INCLUDE_KTS.search(stripped)
        if m:
            for part in m.group(1).split(","):
                part = part.strip().strip("'\"").strip()
                if part:
                    module_names.add(_normalize_gradle_name(part))

    # Map each module to its build file path
    module_build_paths = {}
    for module_name in module_names:
        for gradle_name in (
            f"{module_name}/build.gradle",
            f"{module_name}/build.gradle.kts",
        ):
            p = root / gradle_name
            if p.exists():
                module_build_paths[module_name] = p
                break

    # Scan each module's build file for dependencies
    for module_name, build_path in module_build_paths.items():
        if module_name not in graph:
            graph[module_name] = set()
        build_text = build_path.read_text(encoding="utf-8", errors="ignore")
        for line in build_text.splitlines():
            stripped = line.strip()
            if _RE_COMMENT.match(stripped) or _RE_INTERPOLATION.search(stripped):
                continue
            for pattern in (
                _RE_PROJECT_DEP,
                _RE_PROJECT_DEP_KTS,
                _RE_API_DEP,
                _RE_COMPILEONLY_DEP,
            ):
                m = pattern.search(stripped)
                if m:
                    graph[module_name].add(_normalize_gradle_name(m.group(1)))

    return graph


def _detect_circular_deps(graph):
    """DFS-based cycle detector. Returns list of (module_a, module_b) directed edges in cycles."""
    cycles = []
    visited = set()
    rec_stack = set()

    def dfs(node, path):
        visited.add(node)
        rec_stack.add(node)
        path = path + [node]
        for neighbor in graph.get(node, set()):
            if neighbor not in visited:
                if dfs(neighbor, path):
                    return True
            elif neighbor in rec_stack:
                cycles.append((neighbor, node))
                return True
        rec_stack.remove(node)
        return False

    for node in graph:
        if node not in visited:
            dfs(node, [])
    return cycles


def check_circular_gradle_deps(config, args):
    """Check for circular dependencies in the Gradle module graph.

    Respects architecture.generated_code_paths for exemption.
    Returns (violations, exempted_count).
    """
    if getattr(args, "no_gradle", False):
        return [], 0

    arch = config.get("architecture") or {}
    generated_globs = arch.get("generated_code_paths") or []

    graph = scan_gradle_modules(ROOT)
    if not graph:
        return [], 0

    all_cycles = _detect_circular_deps(graph)
    if not all_cycles:
        return [], 0

    violations = []
    for module_a, module_b in all_cycles:
        exempt_a = any(
            fnmatch.fnmatch(module_a, g)
            or fnmatch.fnmatch(module_a.replace(":", ""), g)
            for g in generated_globs
        )
        exempt_b = any(
            fnmatch.fnmatch(module_b, g)
            or fnmatch.fnmatch(module_b.replace(":", ""), g)
            for g in generated_globs
        )
        if not (exempt_a or exempt_b):
            violations.append((module_a, module_b))
    exempted = len(all_cycles) - len(violations)
    return violations, exempted


def check_isolation_violations(rules, layers, config):
    violations = []
    if not rules:
        return violations
    for path in iter_source_files(layers, config):
        relative = path.resolve().relative_to(ROOT.resolve())
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_number, reference in extract_imports(text, path.suffix.lower()):
            normalized_reference = normalize_reference(reference, relative)
            for rule in rules:
                if not reference_matches_prefix(normalized_reference, rule["import_prefix"]):
                    continue
                if path_allowed_by_isolation(relative, rule["allowed_paths"]):
                    continue
                violations.append(
                    {
                        "path": relative.as_posix(),
                        "line": line_number,
                        "reference": normalized_reference,
                        "import_prefix": rule["import_prefix"],
                        "allowed_paths": rule["allowed_paths"],
                        "note": rule["note"],
                    }
                )
    return violations


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------
def main():
    args = parse_args()
    try:
        config = load_config()
    except FileNotFoundError:
        problem(
            "Harness configuration was not found.",
            "lint_deps.py needs harness.yaml or harness.json at the project root.",
            "Create harness.yaml with architecture.layers, or generate it from harness.example.yaml during project initialization.",
        )
        return 1
    except (ValueError, json.JSONDecodeError) as error:
        problem(
            "Harness configuration could not be parsed.",
            str(error),
            "Fix the configuration syntax and run lint_deps.py again.",
        )
        return 1

    layers = get_layers(config)
    isolation_rules = get_isolation_rules(config)
    isolation_config_errors = validate_isolation_config(isolation_rules)

    violations = 0
    for field_path, message in isolation_config_errors:
        violations += 1
        err(
            field_path,
            message,
            "Import isolation rules must be explicit and portable.",
            "Set import_prefix and repository-relative allowed_paths entries.",
        )

    for item in check_isolation_violations(isolation_rules, layers, config):
        violations += 1
        allowed = ", ".join(item["allowed_paths"]) or "<none>"
        print(f"[VIOLATION] {item['path']}:{item['line']}")
        print(f"  imports quarantined prefix: {item['reference']}")
        print(f"  Rule: import_prefix \"{item['import_prefix']}\" is allowed only under {allowed}")
        if item["note"]:
            print(f"  Note: {item['note']}")
        print("  Fix: Move the import behind the allowed bridge/quarantine path or remove the dependency")

    # OQ-A-3: Empty layers → INFO + exit 0 when no isolation violations exist.
    if not layers:
        info(
            "architecture.layers",
            "No architecture layers are configured.",
            "Dependency validation requires architecture.layers to determine allowed import direction. "
            "With no layers defined, architecture-level dependency checking is skipped.",
            "Add architecture.layers to harness.yaml or harness.json to enable dependency direction checks. "
            "This is normal for early-stage projects (ref-to-design/audit phase) before architecture is defined.",
        )
        return 1 if violations else 0

    warn_on_unresolved = getattr(args, "warn_on_unresolved", False)

    for path in iter_source_files(layers, config):
        relative = path.resolve().relative_to(ROOT.resolve())
        source_layer = layer_for_path(relative, layers)
        if not source_layer:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_number, reference in extract_imports(text, path.suffix.lower()):
            normalized_reference = normalize_reference(reference, relative)
            imported_layer = layer_from_reference(normalized_reference, layers)
            if not imported_layer or imported_layer == source_layer:
                # Unresolved import (not mapped to any layer)
                if warn_on_unresolved:
                    warn(
                        f"{relative.as_posix()}:{line_number}",
                        f"Could not resolve import '{normalized_reference}' to any architecture layer.",
                        "All imports in checked source files should resolve to a defined architecture layer.",
                        "Add a layer entry in architecture.layers that matches this import, "
                        "or move the imported code to a layer that is already defined.",
                    )
                # else: silently skip (old behaviour for unresolved)
                continue
            if int(imported_layer["level"]) >= int(source_layer["level"]):
                continue
            violations += 1
            print(f"[VIOLATION] {relative.as_posix()}:{line_number}")
            print(f"  imports from: {normalized_reference}")
            print(
                f'  Rule: Layer "{source_layer["name"]}" (level {source_layer["level"]}) '
                f'cannot import from layer "{imported_layer["name"]}" (level {imported_layer["level"]})'
            )
            print("  Fix: Move shared logic to a common/shared module")
            if args.fix_suggestions:
                print(
                    "  Suggestion: Extract an interface or shared helper owned by an equal or lower layer"
                )

    # Gradle circular dependency check
    gradle_violations, gradle_exempted = check_circular_gradle_deps(config, args)
    if gradle_violations:
        for module_a, module_b in gradle_violations:
            violations += 1
            warn(
                f"gradle.module.{module_a} <-> gradle.module.{module_b}",
                f"Gradle module circular dependency detected: {module_a} ↔ {module_b}.",
                f"Module '{module_a}' and module '{module_b}' depend on each other directly or transitively. "
                "Circular dependencies prevent independent build and testing.",
                "Break the cycle by extracting a shared interface or moving shared code to a third module. "
                "Use migration-roadmap to plan the refactoring in phases.",
            )
    if gradle_exempted > 0:
        print(
            f"[INFO] Gradle circular deps: {gradle_exempted} cycle(s) exempted by architecture.generated_code_paths"
        )

    if violations:
        return 1
    print("[OK] No dependency direction violations found")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
