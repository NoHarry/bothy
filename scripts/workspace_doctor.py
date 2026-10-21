# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Diagnose a developer workspace and point to the next safe action."""

import argparse
import importlib
import json
import shutil
import subprocess
import sys
from datetime import date
from pathlib import Path

from harness_runtime import display_path, load_yaml, trace_path


ROOT = Path(__file__).resolve().parents[1]


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


def load_config():
    for name in ("harness.local.yaml", "harness.yaml", "harness.example.yaml"):
        path = ROOT / name
        if not path.exists():
            continue
        data = load_yaml(path)
        return data if isinstance(data, dict) else {}
    return {}


def run(command, check=False):
    result = subprocess.run(
        command,
        cwd=ROOT,
        shell=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result


def git(args):
    return run("git " + " ".join(args))


def add(report, group, message):
    report.setdefault(group, []).append(message)


def is_git_repo():
    return git(["rev-parse", "--is-inside-work-tree"]).returncode == 0


def detect_project_tools(config):
    tools = {"git", "python"}
    project = config.get("project") if isinstance(config.get("project"), dict) else {}
    platform = str(project.get("platform") or "").lower()
    platforms = config.get("platforms") if isinstance(config.get("platforms"), dict) else {}
    platform_names = {platform, *[str(name).lower() for name in platforms]}
    file_names = {path.name for path in ROOT.iterdir()}
    gradle_markers = {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}
    if "android" in platform_names or bool(file_names & gradle_markers):
        tools.update({"java", "android"})
    if "web" in platform_names or (ROOT / "package.json").exists():
        tools.update({"node", "npm"})
    if (ROOT / "go.mod").exists():
        tools.add("go")
    if (ROOT / "Cargo.toml").exists():
        tools.add("cargo")
    return sorted(tools)


def check_git(report):
    if not is_git_repo():
        add(report, "SELF-FIX", "Git repository not initialized. Run git init before installing hooks.")
        return False
    branch = git(["branch", "--show-current"]).stdout.strip() or "<detached>"
    add(report, "READ-FIRST", f"Current branch: {branch}")
    remote = git(["remote", "-v"]).stdout.strip()
    if remote:
        add(report, "READ-FIRST", "Remote is configured.")
    else:
        add(report, "OWNER-ACTION", "No git remote configured. Use bootstrap hook profile until remote policy is known.")
    upstream = git(["rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"])
    if upstream.returncode == 0 and upstream.stdout.strip():
        add(report, "READ-FIRST", f"Upstream: {upstream.stdout.strip()}")
    else:
        add(report, "SELF-FIX", "No upstream branch is configured for the current branch.")
    status = git(["status", "--short"]).stdout.strip()
    if status:
        add(report, "READ-FIRST", "Workspace has uncommitted changes; inspect git status before editing.")
    else:
        add(report, "READ-FIRST", "Workspace is clean.")
    hooks = git(["config", "--local", "--get", "core.hooksPath"])
    hooks_path = hooks.stdout.strip() if hooks.returncode == 0 else ""
    if hooks_path.replace("\\", "/").rstrip("/") in {".githooks", "./.githooks"}:
        add(report, "READ-FIRST", "Harness git hooks are installed.")
    else:
        add(report, "SELF-FIX", "Harness git hooks are not installed.")
    recent = git(["log", "--oneline", "-5"])
    if recent.returncode == 0 and recent.stdout.strip():
        add(report, "READ-FIRST", "Recent commits:\n" + recent.stdout.strip())
    return bool(remote)


def check_tools(report, config):
    for tool in detect_project_tools(config):
        if shutil.which(tool):
            add(report, "CHECKS", f"{tool}: found")
        else:
            add(report, "SELF-FIX", f"{tool}: missing from PATH")
    if "android" in detect_project_tools(config):
        has_android_home = bool(
            __import__("os").environ.get("ANDROID_HOME")
            or __import__("os").environ.get("ANDROID_SDK_ROOT")
            or (ROOT / "local.properties").exists()
        )
        if has_android_home:
            add(report, "CHECKS", "Android SDK path is configured.")
        else:
            add(report, "SELF-FIX", "Android SDK path is missing. Set ANDROID_HOME/ANDROID_SDK_ROOT or local.properties.")


def check_python_runtime(report):
    version = ".".join(str(part) for part in sys.version_info[:3])
    if sys.version_info < (3, 9):
        add(
            report,
            "SELF-FIX",
            f"Python {version} is below the supported Harness runtime baseline. Use Python 3.9 or newer.",
        )
    else:
        add(report, "CHECKS", f"Python runtime: {version}")
    try:
        module = importlib.import_module("yaml")
    except ImportError:
        add(
            report,
            "SELF-FIX",
            "PyYAML is not installed. Install dependencies from harness/requirements.txt before using advanced YAML syntax.",
        )
        return
    version_attr = getattr(module, "__version__", "unknown")
    add(report, "CHECKS", f"PyYAML: found ({version_attr})")


def check_docs(report):
    for name in ("README.md", "AGENTS.md", "CONSTRAINTS.md", "BLACKBOARD.md", "WORKFLOW.md"):
        if (ROOT / name).exists():
            add(report, "READ-FIRST", f"Read {name}.")
        else:
            add(report, "OWNER-ACTION", f"Missing {name}; project onboarding map is incomplete.")
    if (ROOT / "harness" / "docs" / "first-week-handoff.md").exists():
        add(report, "READ-FIRST", "Read harness/docs/first-week-handoff.md for owner handoff flow.")
    if (ROOT / "harness" / "docs" / "workspace-doctor.md").exists():
        add(report, "READ-FIRST", "Read harness/docs/workspace-doctor.md for this diagnostic flow.")


def check_agent_tool_adapters(report):
    required = [
        "scripts/agent_runtime.py",
        "scripts/dream_runner.py",
        "scripts/hook_bus.py",
        "scripts/validate_workflow.py",
        "scripts/lint_memory.py",
        "scripts/sync_skills.py",
        "harness/docs/agent-runtime-contract.md",
        "harness/docs/workflow-contract.md",
        "harness/docs/eval-governance.md",
        "harness/evals/index.yaml",
        "harness/policy.example.yaml",
        ".agent-harness/hooks/adapters/README.md",
    ]
    missing = [path for path in required if not (ROOT / path).exists()]
    if missing:
        add(report, "OWNER-ACTION", "Missing Harness agent-tool adapter files: " + ", ".join(missing))
    else:
        add(report, "CHECKS", "Harness runtime, eval governance, dream, hook bus, memory lint, and skills-sync scaffolding is present.")

    projections = {
        "codex": ROOT / ".agents" / "skills",
        "claude": ROOT / ".claude" / "skills",
        "opencode": ROOT / ".opencode" / "skills",
    }
    for name, path in projections.items():
        if path.exists():
            count = len(list(path.glob("*/SKILL.md")))
            add(report, "CHECKS", f"{name} skill projection present with {count} skill(s).")
        else:
            add(report, "READ-FIRST", f"{name} skill projection is not active in this workspace.")


def check_agent_runtime(report, apply_fix=False):
    script = ROOT / "scripts" / "agent_runtime.py"
    if not script.exists():
        add(report, "OWNER-ACTION", "Missing scripts/agent_runtime.py; agent runtime contract is unavailable.")
        return
    manifest = ROOT / "harness" / ".local" / "agent-runtime" / "manifest.json"
    if manifest.exists():
        add(report, "CHECKS", "Agent runtime manifest is initialized under harness/.local/agent-runtime/.")
        return
    if apply_fix:
        result = run("python scripts/agent_runtime.py init")
        if result.returncode == 0:
            add(report, "AUTO-FIXED", "Initialized local agent runtime under harness/.local/agent-runtime/.")
        else:
            add(report, "SELF-FIX", "Agent runtime init failed:\n" + (result.stdout + result.stderr).strip())
    else:
        add(report, "SELF-FIX", "Agent runtime is not initialized. Run `python scripts/agent_runtime.py init` or rerun workspace-doctor with --fix-safe.")


def check_resumable_runs(report):
    script = ROOT / "scripts" / "agent_runtime.py"
    if not script.exists():
        return
    result = run("python scripts/agent_runtime.py next --json")
    if result.returncode != 0:
        add(report, "CHECKS", "FAIL: python scripts/agent_runtime.py next --json")
        return
    try:
        data = json.loads(result.stdout)
    except json.JSONDecodeError:
        add(report, "CHECKS", "FAIL: agent runtime next output was not valid JSON.")
        return
    runs = data.get("runs") if isinstance(data, dict) else []
    if not runs:
        add(report, "CHECKS", "No resumable agent runtime runs.")
        return
    for item in runs[:5]:
        next_items = item.get("next") or ["Continue from the objective and recent events."]
        workpad = item.get("workpad") if isinstance(item.get("workpad"), dict) else {}
        pending_validation = workpad.get("pending_validation") or []
        validation_note = f"\nPending validation: {pending_validation[0]}" if pending_validation else ""
        pending_count = workpad.get("pending_completion_count", 0)
        workpad_path = workpad.get("workpad_path", "")
        add(
            report,
            "RESUME-FIRST",
            f"{item.get('status', 'unknown')}: {item.get('objective', item.get('run_id'))} "
            f"({item.get('run_id')})\nNext: {next_items[0]}"
            f"\nPending completion: {pending_count}{validation_note}"
            f"\nWorkpad: {workpad_path}"
            f"\nResume: {item.get('resume_command')}",
        )


def check_owner_actions(report, config):
    hardening_debt = config.get("hardening_debt")
    if isinstance(hardening_debt, list) and hardening_debt:
        add(report, "OWNER-ACTION", f"hardening_debt has {len(hardening_debt)} unresolved item(s).")
    validation = config.get("validation") if isinstance(config.get("validation"), dict) else {}
    for key in ("build", "test"):
        step = validation.get(key) if isinstance(validation.get(key), dict) else {}
        if step and step.get("enabled") is False:
            add(report, "OWNER-ACTION", f"validation.{key}.enabled is false; owner should decide when to enable it.")
    placeholders = []
    for name in ("AGENTS.md", "CONSTRAINTS.md", "README.md", "harness.example.yaml"):
        path = ROOT / name
        if path.exists() and "{PLACEHOLDER}" in path.read_text(encoding="utf-8", errors="ignore"):
            placeholders.append(name)
    if placeholders:
        add(report, "OWNER-ACTION", "Placeholder markers remain in: " + ", ".join(placeholders))


def run_checks(report):
    commands = [
        "python scripts/sync_skills.py --check",
        "python scripts/agent_runtime.py status --json",
        "python scripts/validate_workflow.py",
        "python scripts/lint_memory.py",
        "python scripts/lint_deps.py",
        "python scripts/lint_quality.py",
        "python scripts/validate.py --dry-run",
    ]
    for command in commands:
        result = run(command)
        status = "PASS" if result.returncode == 0 else "FAIL"
        add(report, "CHECKS", f"{status}: {command}")


def fix_safe(report, has_remote):
    if not is_git_repo():
        add(report, "SELF-FIX", "Skipped hook install because this directory is not a git repository.")
        return
    profile = "baseline" if has_remote else "bootstrap"
    result = run(f"python scripts/install_git_hooks.py --mode install --profile {profile}")
    if result.returncode == 0:
        add(report, "AUTO-FIXED", f"Installed Harness git hooks with profile={profile}.")
    else:
        add(report, "OWNER-ACTION", "Hook installation failed:\n" + (result.stdout + result.stderr).strip())


def next_steps(report):
    if report.get("RESUME-FIRST"):
        add(report, "NEXT-STEPS", "Review RESUME-FIRST handoff before starting a new plan or implementation.")
    if report.get("SELF-FIX"):
        add(report, "NEXT-STEPS", "Fix SELF-FIX items on the developer machine, then rerun workspace-doctor.")
    if report.get("OWNER-ACTION"):
        add(report, "NEXT-STEPS", "Send OWNER-ACTION items to the project owner; do not guess project policy.")
    if not report.get("SELF-FIX") and not report.get("OWNER-ACTION"):
        add(report, "NEXT-STEPS", "Workspace is ready for the requested harness skill or normal development flow.")


def write_trace(report):
    path = trace_path(ROOT, f"{date.today().isoformat()}-workspace-doctor.md")
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Workspace Doctor Report", ""]
    for group in ("RESUME-FIRST", "AUTO-FIXED", "SELF-FIX", "OWNER-ACTION", "READ-FIRST", "CHECKS", "NEXT-STEPS"):
        lines.extend([f"## {group}", ""])
        for item in report.get(group, []):
            lines.append(f"- {item}")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def print_report(report):
    for group in ("RESUME-FIRST", "AUTO-FIXED", "SELF-FIX", "OWNER-ACTION", "READ-FIRST", "CHECKS", "NEXT-STEPS"):
        print(f"\n## {group}")
        items = report.get(group, [])
        if not items:
            print("- none")
            continue
        for item in items:
            print(f"- {item}")


def parse_args():
    parser = argparse.ArgumentParser(description="Diagnose local workspace readiness.")
    _ = parser.add_argument("--fix-safe", action="store_true", help="Apply safe local fixes such as hook installation.")
    _ = parser.add_argument("--run-checks", action="store_true", help="Run non-destructive validation checks.")
    _ = parser.add_argument("--write-trace", action="store_true", help="Write harness/trace workspace-doctor report.")
    _ = parser.add_argument("--json", action="store_true", help="Output machine-readable JSON.")
    return parser.parse_args()


def main():
    args = parse_args()
    config_error = ""
    try:
        config = load_config()
    except ValueError as error:
        config = {}
        config_error = str(error)
    report = {}
    if config_error:
        add(
            report,
            "SELF-FIX",
            f"Harness config could not be parsed; diagnosis continues without it: {config_error}",
        )
    has_remote = check_git(report)
    check_tools(report, config)
    check_python_runtime(report)
    check_docs(report)
    check_agent_tool_adapters(report)
    check_agent_runtime(report, args.fix_safe)
    check_resumable_runs(report)
    check_owner_actions(report, config)
    if args.fix_safe:
        fix_safe(report, has_remote)
    if args.run_checks:
        run_checks(report)
    next_steps(report)
    if args.write_trace:
        path = write_trace(report)
        add(report, "AUTO-FIXED", f"Wrote trace report: {display_path(ROOT, path)}")
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print_report(report)
    return 2 if report.get("SELF-FIX") or report.get("OWNER-ACTION") else 0


if __name__ == "__main__":
    raise SystemExit(main())
