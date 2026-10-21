#!/usr/bin/env python3
"""Public-repo hygiene gate.

This repository is public. Every commit must pass three checks:

1. Identity: the effective git author identity must be the project's public
   identity, so private/corporate identities never enter public history.
2. Generic secrets: staged added lines must not contain common credential
   patterns (API keys, private key blocks, tokens).
3. Local denylist: if the ignored file ``harness/.local/denylist.txt`` exists,
   staged added lines and staged paths must not contain any listed term.
   The denylist itself is machine-local and must never be committed; when the
   file is absent this check passes silently (external contributors are
   unaffected).

Each violation is reported as: what was violated, why it matters, how to fix.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DENYLIST_PATH = ROOT / "harness" / ".local" / "denylist.txt"

ALLOWED_EMAIL = "noharry@users.noreply.github.com"
ALLOWED_NAME = "NoHarry"

SECRET_PATTERNS = [
    (re.compile(r"sk-[A-Za-z0-9]{16,}"), "OpenAI-style API key"),
    (re.compile(r"AKIA[0-9A-Z]{16}"), "AWS access key id"),
    (re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"), "GitHub token"),
    (re.compile(r"github_pat_[A-Za-z0-9_]{20,}"), "GitHub fine-grained token"),
    (re.compile(r"AIza[0-9A-Za-z_\-]{30,}"), "Google API key"),
    (re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}"), "Slack token"),
    (re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----"), "private key block"),
    (re.compile(r"eyJ[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{20,}\.[A-Za-z0-9_\-]{10,}"), "JWT"),
]

FORBIDDEN_PATH_PATTERNS = [
    (re.compile(r"(^|/)local\.properties$"), "local.properties carries machine-local keys"),
    (re.compile(r"\.(jks|keystore|p12|pfx)$"), "keystore/certificate bundle with private material"),
    (re.compile(r"(^|/)harness/\.local(/|$)"), "harness/.local is machine-local runtime state"),
]


def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
    )
    return result.stdout


def staged_paths() -> list[str]:
    output = run_git("diff", "--cached", "--name-only", "--diff-filter=ACMR")
    return [line.strip() for line in output.splitlines() if line.strip()]


def staged_added_lines() -> list[tuple[str, int, str]]:
    """Return (path, line_number, text) for every staged added line."""
    output = run_git("diff", "--cached", "--unified=0", "--diff-filter=ACMR")
    entries: list[tuple[str, int, str]] = []
    path = ""
    line_no = 0
    for raw in output.splitlines():
        if raw.startswith("+++ b/"):
            path = raw[6:]
        elif raw.startswith("@@"):
            match = re.search(r"\+(\d+)", raw)
            line_no = int(match.group(1)) if match else 0
        elif raw.startswith("+") and not raw.startswith("+++"):
            entries.append((path, line_no, raw[1:]))
            line_no += 1
    return entries


def check_identity(errors: list[str]) -> None:
    email = run_git("config", "user.email").strip()
    name = run_git("config", "user.name").strip()
    if email != ALLOWED_EMAIL or name != ALLOWED_NAME:
        errors.append(
            "Identity gate: effective git identity is "
            f"'{name} <{email}>'.\n"
            "  Why: commits to this public repository must never carry a "
            "private or corporate identity.\n"
            f"  Fix: git config user.name {ALLOWED_NAME} && "
            f"git config user.email {ALLOWED_EMAIL}"
        )


def check_paths(paths: list[str], errors: list[str]) -> None:
    for path in paths:
        for pattern, reason in FORBIDDEN_PATH_PATTERNS:
            if pattern.search(path):
                errors.append(
                    f"Forbidden path staged: {path}\n"
                    f"  Why: {reason}; it must stay out of the public repository.\n"
                    "  Fix: git restore --staged -- "
                    f"'{path}' and keep it ignored."
                )


def check_secrets(lines: list[tuple[str, int, str]], errors: list[str]) -> None:
    for path, line_no, text in lines:
        for pattern, label in SECRET_PATTERNS:
            if pattern.search(text):
                errors.append(
                    f"Possible {label} at {path}:{line_no}\n"
                    "  Why: credentials in a public repository are compromised "
                    "the moment they are pushed.\n"
                    "  Fix: remove the value, load it from an ignored local "
                    "file, and rotate the credential if it was real."
                )


def load_denylist() -> list[str]:
    if not DENYLIST_PATH.is_file():
        return []
    terms: list[str] = []
    for raw in DENYLIST_PATH.read_text(encoding="utf-8").splitlines():
        term = raw.strip()
        if term and not term.startswith("#"):
            terms.append(term)
    return terms


def check_denylist(
    paths: list[str], lines: list[tuple[str, int, str]], errors: list[str]
) -> None:
    terms = load_denylist()
    if not terms:
        return
    lowered = [(term, term.lower()) for term in terms]
    for path in paths:
        for term, needle in lowered:
            if needle in path.lower():
                errors.append(
                    f"Denylisted term '{term}' in staged path: {path}\n"
                    "  Why: this term is on the machine-local denylist for "
                    "this public repository.\n"
                    "  Fix: rename or unstage the file."
                )
    for path, line_no, text in lines:
        haystack = text.lower()
        for term, needle in lowered:
            if needle in haystack:
                errors.append(
                    f"Denylisted term '{term}' at {path}:{line_no}\n"
                    "  Why: this term is on the machine-local denylist for "
                    "this public repository.\n"
                    "  Fix: rewrite the line without the term before committing."
                )


def main() -> int:
    errors: list[str] = []
    check_identity(errors)
    paths = staged_paths()
    lines = staged_added_lines()
    check_paths(paths, errors)
    check_secrets(lines, errors)
    check_denylist(paths, lines, errors)
    if errors:
        print("[FAIL] Public hygiene gate found issues:\n")
        for error in errors:
            print(f"- {error}\n")
        return 1
    print("[OK] Public hygiene gate passed (identity, secrets, denylist).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
