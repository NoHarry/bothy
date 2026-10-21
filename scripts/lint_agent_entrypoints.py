# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Keep agent entrypoints thin and layered maps present."""

from harness_runtime import ROOT, is_template_repo


CANONICAL_CLAUDE_MD = """@AGENTS.md

<!-- Harness adapter only. Do not add project rules here; update AGENTS.md. -->
"""
ROOT_AGENTS_MAX_LINES = 120
REQUIRED_TEMPLATE_AGENT_MAPS = [
    "AGENTS.md",
    ".agent-harness/AGENTS.md",
    ".agent-harness/skills/AGENTS.md",
    ".githooks/AGENTS.md",
    ".review/AGENTS.md",
    "doc/AGENTS.md",
    "harness/AGENTS.md",
    "harness/doc-local/AGENTS.md",
    "harness/docs/AGENTS.md",
    "harness/dream/AGENTS.md",
    "harness/evals/AGENTS.md",
    "harness/memory/AGENTS.md",
    "harness/trace/AGENTS.md",
    "scripts/AGENTS.md",
    "templates/AGENTS.md",
]
GENERATED_AGENT_PREFIXES = (
    ".agents/skills/",
    ".claude/skills/",
    ".opencode/skills/",
)


def normalize(text):
    return text.replace("\r\n", "\n").strip() + "\n"


def lint_claude_adapter():
    path = ROOT / "CLAUDE.md"
    if not path.exists():
        print("[OK] CLAUDE.md is absent; Claude Code adapter entrypoint not active.")
        return 0

    actual = normalize(path.read_text(encoding="utf-8"))
    expected = normalize(CANONICAL_CLAUDE_MD)
    if actual == expected:
        print("[OK] CLAUDE.md is a thin AGENTS.md adapter.")
        return 0

    print("[FAIL] CLAUDE.md must stay a thin adapter that only imports AGENTS.md.")
    print("Do not put project rules, preferences, workflows, or skill instructions in CLAUDE.md.")
    print("Update AGENTS.md or .agent-harness/skills/src/ instead.")
    print("Expected CLAUDE.md content:")
    print(expected)
    return 1


def lint_root_agents_size():
    path = ROOT / "AGENTS.md"
    if not path.exists():
        print("[FAIL] AGENTS.md is missing.")
        return 1
    lines = path.read_text(encoding="utf-8").splitlines()
    if is_template_repo(ROOT) and len(lines) > ROOT_AGENTS_MAX_LINES:
        print(
            f"[FAIL] Root AGENTS.md has {len(lines)} lines; "
            f"template root map must stay <= {ROOT_AGENTS_MAX_LINES} lines."
        )
        print("Move component details into a local AGENTS.md or harness/docs/.")
        return 1
    print(f"[OK] Root AGENTS.md size is acceptable ({len(lines)} lines).")
    return 0


def lint_required_template_maps():
    if not is_template_repo(ROOT):
        print("[OK] Target project mode; layered template AGENTS.md inventory not enforced.")
        return 0
    missing = [item for item in REQUIRED_TEMPLATE_AGENT_MAPS if not (ROOT / item).exists()]
    if not missing:
        print("[OK] Required template layered AGENTS.md files are present.")
        return 0
    print("[FAIL] Missing required template layered AGENTS.md file(s):")
    for item in missing:
        print(f"  - {item}")
    return 1


def lint_generated_projection_maps():
    offenders = []
    for path in ROOT.rglob("AGENTS.md"):
        rel = path.relative_to(ROOT).as_posix()
        if any(rel.startswith(prefix) for prefix in GENERATED_AGENT_PREFIXES):
            offenders.append(rel)
    if not offenders:
        print("[OK] Generated skill projections do not contain AGENTS.md entrypoints.")
        return 0
    print("[FAIL] Generated skill projection directories must not contain AGENTS.md:")
    for item in offenders:
        print(f"  - {item}")
    print("Keep rules in canonical skill sources or allowed local maps instead.")
    return 1


def main():
    checks = [
        lint_claude_adapter(),
        lint_root_agents_size(),
        lint_required_template_maps(),
        lint_generated_projection_maps(),
    ]
    return 1 if any(checks) else 0


if __name__ == "__main__":
    raise SystemExit(main())
