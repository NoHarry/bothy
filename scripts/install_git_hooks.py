# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Install and run repository-local Harness git hooks."""

import argparse
import importlib
import json
import shutil
import subprocess
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_COMMANDS = {
    "pre-commit": [
        "python scripts/sync_skills.py --check",
        "python scripts/lint_agent_entrypoints.py",
        "python scripts/lint_template_clean.py",
        "python scripts/lint_template_version.py",
        "python scripts/lint_harness_config.py",
        "python scripts/lint_goal_contract.py",
        "python scripts/lint_prd_review_signoff.py",
        "python scripts/validate_workflow.py",
        "python scripts/validate_harness_sync.py",
        "python scripts/lint_memory.py",
        "python scripts/lint_deps.py",
        "python scripts/lint_quality.py",
    ],
    "pre-push": ["python scripts/validate.py --dry-run"],
}


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
        text = path.read_text(encoding="utf-8")
        try:
            module = importlib.import_module("yaml")
            loader = getattr(module, "safe_load", None)
        except ImportError:
            loader = None
        data = loader(text) if callable(loader) else parse_yaml(text)
        return data if isinstance(data, dict) else {}
    return {}


def git(args, check=False):
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result


def is_git_repo():
    return git(["rev-parse", "--is-inside-work-tree"]).returncode == 0


def git_path(name):
    result = git(["rev-parse", "--git-path", name], check=True)
    return (ROOT / result.stdout.strip()).resolve()


def git_common_path(name):
    result = git(["rev-parse", "--git-common-dir"], check=True)
    common = Path(result.stdout.strip())
    if not common.is_absolute():
        common = ROOT / common
    return (common / name).resolve()


def current_hooks_path():
    result = git(["config", "--local", "--get", "core.hooksPath"])
    return result.stdout.strip() if result.returncode == 0 else ""


def local_hook_profile():
    result = git(["config", "--local", "--get", "harness.hooksProfile"])
    profile = result.stdout.strip() if result.returncode == 0 else ""
    return profile if profile in {"bootstrap", "baseline", "strict"} else ""


def hook_commands(config, hook):
    hooks = config.get("git_hooks") if isinstance(config.get("git_hooks"), dict) else {}
    key = hook.replace("-", "_")
    hook_cfg = hooks.get(key) if isinstance(hooks.get(key), dict) else {}
    commands = hook_cfg.get("commands") if isinstance(hook_cfg, dict) else None
    if isinstance(commands, list) and commands:
        return [str(command) for command in commands if str(command).strip()]
    return DEFAULT_COMMANDS[hook]


def hook_profile(config, explicit):
    if explicit:
        return explicit
    local_profile = local_hook_profile()
    if local_profile:
        return local_profile
    hooks = config.get("git_hooks") if isinstance(config.get("git_hooks"), dict) else {}
    profile = str(hooks.get("profile") or "baseline").strip()
    return profile if profile in {"bootstrap", "baseline", "strict"} else "baseline"


def ensure_git_repo(init_if_missing):
    if is_git_repo():
        return True
    if not init_if_missing:
        print("[FAIL] Not inside a git repository. Run git init first or pass --init-if-missing.")
        return False
    result = subprocess.run(["git", "init"], cwd=ROOT)
    return result.returncode == 0


def backup_legacy_hooks():
    hooks_dir = git_common_path("hooks")
    state_path = git_path("harness-hooks-state.json")
    state = {"backups": []}
    if state_path.exists():
        try:
            loaded = json.loads(state_path.read_text(encoding="utf-8"))
            if isinstance(loaded, dict):
                state = loaded
        except json.JSONDecodeError:
            state = {"backups": []}

    hooks_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y%m%d%H%M%S")
    for hook in ("pre-commit", "pre-push"):
        source = hooks_dir / hook
        if not source.exists() or not source.is_file():
            continue
        backup = hooks_dir / f"{hook}.harness-backup-{timestamp}"
        shutil.copy2(source, backup)
        state.setdefault("backups", []).append(
            {"hook": hook, "source": str(source), "backup": str(backup)}
        )
        print(f"[INFO] Backed up existing {hook} to {backup}")
    state_path.write_text(json.dumps(state, indent=2), encoding="utf-8")


def install(args):
    if not ensure_git_repo(args.init_if_missing):
        return 1
    existing = current_hooks_path()
    normalized = existing.replace("\\", "/").rstrip("/")
    if normalized and normalized not in {".githooks", "./.githooks"} and not args.force:
        print(f"[FAIL] Existing core.hooksPath is '{existing}'.")
        print("Use --force only after confirming the existing hook chain is no longer needed.")
        return 1
    for hook in ("pre-commit", "pre-push"):
        hook_path = ROOT / ".githooks" / hook
        if not hook_path.exists():
            print(f"[FAIL] Missing hook file: {hook_path}")
            return 1
    if normalized not in {".githooks", "./.githooks"}:
        backup_legacy_hooks()
    git(["config", "--local", "core.hooksPath", ".githooks"], check=True)
    git(["config", "--local", "harness.hooksProfile", args.profile], check=True)
    print(f"[OK] Installed Harness git hooks with profile={args.profile}.")
    return 0


def uninstall():
    if not is_git_repo():
        print("[FAIL] Not inside a git repository.")
        return 1
    existing = current_hooks_path()
    if existing.replace("\\", "/").rstrip("/") in {".githooks", "./.githooks"}:
        git(["config", "--local", "--unset", "core.hooksPath"])
        git(["config", "--local", "--unset", "harness.hooksProfile"])
        print("[OK] Removed Harness core.hooksPath.")
        return 0
    print(f"[INFO] Harness hooks are not active. core.hooksPath={existing or '<unset>'}")
    return 0


def status():
    if not is_git_repo():
        print("[FAIL] Not inside a git repository.")
        return 1
    config = load_config()
    profile = hook_profile(config, "")
    path = current_hooks_path()
    expected = path.replace("\\", "/").rstrip("/") in {".githooks", "./.githooks"}
    print(f"Git hooks path: {path or '<unset>'}")
    print(f"Harness hooks active: {'yes' if expected else 'no'}")
    print(f"Harness hook profile: {profile}")
    return 0 if expected else 2


def run_hook(args):
    if args.hook not in DEFAULT_COMMANDS:
        print(f"[FAIL] Unsupported hook: {args.hook}")
        return 1
    config = load_config()
    profile = hook_profile(config, args.profile)
    commands = hook_commands(config, args.hook)
    print(f"[HARNESS] Running {args.hook} hooks with profile={profile}")
    failed = False
    for command in commands:
        print(f"[RUN] {command}")
        result = subprocess.run(command, shell=True, cwd=ROOT)
        if result.returncode == 0:
            continue
        failed = True
        print(f"[FAIL] {command} exited with {result.returncode}")
        if profile != "bootstrap":
            return result.returncode or 1
    if failed and profile == "bootstrap":
        print("[WARN] Hook failures are non-blocking in bootstrap profile.")
    return 0


def parse_args():
    parser = argparse.ArgumentParser(description="Install or run Harness git hooks.")
    _ = parser.add_argument(
        "--mode",
        choices=["install", "status", "uninstall", "run-hook"],
        required=True,
    )
    _ = parser.add_argument("--hook", choices=["pre-commit", "pre-push"])
    _ = parser.add_argument(
        "--profile",
        choices=["bootstrap", "baseline", "strict"],
        default="",
    )
    _ = parser.add_argument("--force", action="store_true")
    _ = parser.add_argument("--init-if-missing", action="store_true")
    return parser.parse_args()


def main():
    args = parse_args()
    if args.mode == "install":
        if not args.profile:
            args.profile = "baseline"
        return install(args)
    if args.mode == "status":
        return status()
    if args.mode == "uninstall":
        return uninstall()
    if args.mode == "run-hook":
        if not args.hook:
            print("[FAIL] --hook is required with --mode run-hook")
            return 1
        return run_hook(args)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
