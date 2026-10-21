# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Prevent template repository runtime records from being committed."""

import subprocess
from pathlib import Path

from harness_runtime import ROOT, is_template_repo


BLOCKED_EXACT = {
    "commit_log.csv",
    "commit_log.xlsx",
    "harness/baseline.json",
    "harness/memory/performance.md",
}

BLOCKED_PREFIXES = {
    "harness/.local/",
    "harness/dream/reports/",
    "harness/trace/hook-events/",
    "harness/upgrade-backups/",
}


def run_git(args):
    return subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def normalize(path_text):
    return path_text.replace("\\", "/").strip()


def candidate_files():
    files = set()
    commands = [
        ["diff", "--name-only"],
        ["diff", "--cached", "--name-only"],
        ["ls-files", "--others", "--exclude-standard"],
        ["ls-files"],
    ]
    for command in commands:
        result = run_git(command)
        if result.returncode != 0:
            continue
        files.update(normalize(line) for line in result.stdout.splitlines() if line)
    return files


def deleted_records():
    deleted = set()
    for command in (["diff", "--name-status"], ["diff", "--cached", "--name-status"]):
        result = run_git(command)
        if result.returncode != 0:
            continue
        for line in result.stdout.splitlines():
            status, _, path = line.partition("\t")
            if status == "D" and path:
                deleted.add(normalize(path))
    return deleted


def is_blocked(path_text):
    path = normalize(path_text)
    if path in BLOCKED_EXACT:
        return True
    if any(path.startswith(prefix) for prefix in BLOCKED_PREFIXES):
        return True
    if path.startswith("harness/trace/"):
        name = Path(path).name
        return len(name) >= 11 and name[:4].isdigit() and name[4] == "-"
    return False


def main():
    if not is_template_repo(ROOT):
        print("[OK] Target project mode; template runtime cleanliness check skipped.")
        return 0

    deleting = deleted_records()
    blocked = sorted(
        path for path in candidate_files() if is_blocked(path) and path not in deleting
    )
    if not blocked:
        print("[OK] Template runtime records are local-only.")
        return 0

    print("[FAIL] Template runtime records must not be committed.")
    print("Move these records to harness/.local/ or remove them from the index:")
    for path in blocked:
        print(f"  - {path}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
