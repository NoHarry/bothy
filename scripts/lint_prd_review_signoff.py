# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Ensure PRD review approvals are centralized in product-spec-review.md."""

import argparse
import re
from pathlib import Path

from harness_runtime import ROOT


SIGNOFF_PATTERNS = [
    re.compile(r"^\s*##+\s+.*(?:最终签收|待签署清单|Owner Sign-?Off|Final Sign-?Off)\s*$", re.I),
    re.compile(r"^\s*\*\*(?:签字|Signature)\*\*\s*[:：]", re.I),
    re.compile(r"^\s*\|\s*Owner\s*\|.*\|\s*Signature\s*\|", re.I),
    re.compile(r"^\s*\|\s*(?:负责人|Owner)\s*\|.*(?:签署|签字|Signature)", re.I),
]

PRD_REVIEW_CONTEXT = (
    "product-spec-review",
    "functional-spec",
    "review-conclusion",
    "doc/features/",
    "Product/API sign-off",
    "Phase 0",
    "PRD",
)


def rel(root, path):
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


def is_product_spec_review(root, path):
    relative = rel(root, path)
    return bool(
        re.match(
            r"^(?:doc/features|harness/doc-local)/[^/]+/(?:generated|outputs)/product-spec-review\.md$",
            relative,
        )
    )


def is_template_path(root, path):
    relative = rel(root, path)
    return relative.startswith("doc/features/templates/")


def iter_markdown_files(root):
    for base in (root / "doc" / "features", root / "harness" / "doc-local", root / "docs"):
        if base.exists():
            yield from sorted(base.rglob("*.md"))


def signoff_hits(text):
    hits = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        for pattern in SIGNOFF_PATTERNS:
            if pattern.search(line):
                hits.append((line_number, line.strip()))
                break
    return hits


def is_prd_review_related(path, text):
    normalized_path = path.as_posix()
    if "/doc/features/" in normalized_path or "/harness/doc-local/" in normalized_path:
        return True
    return any(marker in text for marker in PRD_REVIEW_CONTEXT)


def main():
    parser = argparse.ArgumentParser(
        description="Reject PRD-review sign-off ledgers outside product-spec-review.md."
    )
    _ = parser.add_argument(
        "--root",
        default=str(ROOT),
        help="Repository root to check. Defaults to the current Harness repository.",
    )
    args = parser.parse_args()
    root = Path(args.root).resolve()

    errors = []
    for path in iter_markdown_files(root):
        if is_template_path(root, path) or is_product_spec_review(root, path):
            continue
        text = path.read_text(encoding="utf-8")
        if not is_prd_review_related(path, text):
            continue
        for line_number, line in signoff_hits(text):
            errors.append(
                f"{rel(root, path)}:{line_number} contains PRD-review sign-off marker outside product-spec-review.md: {line}"
            )

    if errors:
        print("[FAIL] PRD review sign-off must be centralized in product-spec-review.md.")
        for message in errors:
            print(f"  - {message}")
        print(
            "How: move owner signatures, gate approval tables, freeze/readiness sign-off, and file approval ledgers "
            "into doc/features/{feature}/generated|outputs/product-spec-review.md. Supporting docs may describe decisions "
            "but must link back to product-spec-review.md as the only approval source."
        )
        return 1

    print("[OK] PRD review sign-off is centralized.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
