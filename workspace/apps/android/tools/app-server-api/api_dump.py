"""The app server's public API as text, checked against agent-app-server/api/agent-app-server.api.

What a host may use: public declarations of :agent-app-server that are not marked @InternalAppServerApi, with the
public members of its public classes, objects and interfaces (overrides left out), one line each: where it is, then
its header with comments and line breaks taken out. A change to that surface fails the check until the file is
written again on purpose.

Usage: python api_dump.py [--write]   (from anywhere; paths are relative to this file)
"""
import argparse
import difflib
import re
import sys
from pathlib import Path

ANDROID = Path(__file__).resolve().parents[2]
SOURCES = ANDROID / "agent-app-server" / "src" / "main" / "java"
DUMP = ANDROID / "agent-app-server" / "api" / "agent-app-server.api"
MARKER = "@InternalAppServerApi"
# Visibility and modifiers, in any order; an annotation may take arguments with one level of parentheses inside.
MODIFIERS = (r"(?:(?:public|internal|private|protected|override|abstract|open|final|const|lateinit|inline|suspend|operator|"
             r"infix|data|enum|sealed|inner|annotation|tailrec|value|companion|expect|actual|external|"
             r"fun(?=\s+interface))\s+)*")
ANNOTATIONS = r"((?:@[\w.:]+(?:\((?:[^()]|\([^()]*\))*\))?\s+)*)"
DECL = re.compile(r"^(\s*)" + ANNOTATIONS + "(" + MODIFIERS + r")(class|interface|object|fun|val|var|typealias|constructor)\b(.*)$")
VISIBILITY = re.compile(r"\b(public|internal|private|protected)\b")
# An enum entry on its own line: its name, then arguments, a body, a comma or the end of the entries.
ENUM_ENTRY = re.compile(r"^\s*([A-Z][A-Za-z0-9_]*)\s*(?:\(.*)?[,;]?\s*\{?\s*$")


class Scan:
    """Kotlin source with comments blanked ([code]) and, per character, the brace depth outside strings (None inside one
    or in a comment)."""

    def __init__(self, text):
        self.text, self.out, self.depths, self.depth, self.stack, self.i = text, [], [], 0, [], 0
        while self.i < len(text):
            top = self.stack[-1] if self.stack else None
            if top in ("line", "block"):
                self.comment(top)
            elif top in ("str", "raw"):
                self.string(top)
            else:
                self.code_char(top)
        self.code = "".join(self.out)

    def emit(self, written, depth=None, length=None):
        self.out.append(written)
        self.depths += [depth] * len(written)
        self.i += len(written) if length is None else length

    def comment(self, top):
        c = self.text[self.i]
        if top == "line" and c == "\n":
            self.stack.pop(); self.emit(c, self.depth)
        elif top == "block" and self.text.startswith("*/", self.i):
            self.stack.pop(); self.emit("  ")
        else:
            self.emit("\n" if c == "\n" else " ")

    def string(self, top):
        end = '"' if top == "str" else '"""'
        if top == "str" and self.text[self.i] == "\\":
            self.emit(self.text[self.i:self.i + 2])
        elif self.text.startswith(end, self.i):
            self.stack.pop(); self.emit(end)
        elif self.text.startswith("${", self.i):
            self.stack.append(["tpl", 0]); self.emit("${")
        else:
            self.emit(self.text[self.i])

    def code_char(self, top):
        text, i = self.text, self.i
        opener = next((o for o in ("//", "/*", '"""', '"') if text.startswith(o, i)), None)
        if opener:
            self.stack.append({"//": "line", "/*": "block", '"""': "raw", '"': "str"}[opener])
            self.emit(opener if opener.startswith('"') else "  ")
        elif text[i] == "'":
            m = re.match(r"'(\\.|[^\\'])+'", text[i:])
            self.emit(text[i:i + (m.end() if m else 1)])
        elif isinstance(top, list):  # inside a string template: its own braces
            if text[i] == "}" and top[1] == 0:
                self.stack.pop()
            elif text[i] in "{}":
                top[1] += 1 if text[i] == "{" else -1
            self.emit(text[i])
        else:
            self.emit(text[i], self.depth)
            self.depth += {"{": 1, "}": -1}.get(text[i], 0)


def header(code, depths, start, delegates=False):
    """The declaration starting at [start], up to its body or initializer, on one line; a secondary constructor's
    ([delegates]) without what it delegates to."""
    nest, i = 0, start
    while i < len(code):
        c = code[i]
        if depths[i] is None:  # inside a string or a comment
            pass
        elif c in "(<[":
            nest += 1
        elif c in ")>]" and not (c == ">" and code[i - 1] == "-"):
            nest = max(0, nest - 1)
        elif nest == 0 and (c == "{" or (c == "=" and code[i - 1] not in "!<>=" and code[i + 1:i + 2] != "=")):
            break
        elif nest == 0 and delegates and c == ":":
            break
        elif nest == 0 and c == "\n":
            rest = code[i + 1:].lstrip(" ")
            if not (rest.startswith((":", ".", "where ")) or code[:i].rstrip().endswith((",", ":", "("))):
                break
        i += 1
    return re.sub(r"\s+", " ", code[start:i]).strip().rstrip(",")


def entries():
    rows = []
    for path in sorted(SOURCES.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        scan = Scan(text)
        code, depths = scan.code, scan.depths
        pkg = re.search(r"^package ([\w.]+)", code, re.M).group(1)
        offset, annotations, owners = 0, [], {}
        for line in code.split("\n"):
            depth = depths[offset] if offset < len(depths) else None
            m = DECL.match(line)
            stripped = line.strip()
            level = (len(line) - len(line.lstrip(" "))) // 4
            parent = owners.get(level - 1) if level else ("", True, False)
            if m and depth is not None:
                indent, annos, mods, kind, rest = m.groups()
                vis = (VISIBILITY.search(mods) or [None, None])[1]
                marked = MARKER in annos or any(MARKER in a for a in annotations)
                name = re.match(r"\s*(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(\w+)", rest) if kind in ("fun", "val", "var") \
                    else re.match(r"\s*(\w+)", rest)
                name = "constructor" if kind == "constructor" else name.group(1) if name else "?"
                if kind == "object" and "companion" in mods:
                    name = "Companion"
                visible = vis in (None, "public", "protected") and not marked and "override" not in mods
                if level == depth and parent is not None and parent[1] and visible:
                    path = f"{parent[0] + '.' if parent[0] else ''}{name}"
                    start = offset + len(indent) + len(annos)
                    rows.append(f"{pkg}.{path} | {header(code, depths, start, delegates=kind == 'constructor')}")
                    owners[level] = (path, kind in ("class", "interface", "object"), "enum" in mods)
                elif level == depth:
                    owners[level] = (name, False, False)
                for deeper in [k for k in owners if k > level]:
                    del owners[deeper]
            elif depth is not None and level == depth and stripped[:1].isalpha() and parent is not None:
                entry = ENUM_ENTRY.match(line) if parent[2] else None
                if entry:
                    # A public enum's entries are its API too.
                    rows.append(f"{pkg}.{parent[0]}.{entry.group(1)} | {entry.group(1)}")
                if "{" in line:
                    # A body nothing above names (an `init` block, an enum entry's): what is declared in it is not the
                    # previous declaration's.
                    owners[level] = (entry.group(1) if entry else "?", False, False)
                    for deeper in [k for k in owners if k > level]:
                        del owners[deeper]
            annotations = annotations + [stripped] if stripped.startswith("@") else []
            offset += len(line) + 1
    return sorted(set(rows))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--write", action="store_true", help="write the dump instead of checking it")
    args = parser.parse_args()
    current = "\n".join(entries()) + "\n"
    if args.write:
        DUMP.parent.mkdir(parents=True, exist_ok=True)
        DUMP.write_text(current, encoding="utf-8")
        print(f"[OK] Wrote {DUMP.relative_to(ANDROID)} ({current.count(chr(10))} declarations).")
        return 0
    saved = DUMP.read_text(encoding="utf-8") if DUMP.exists() else ""
    if saved == current:
        print(f"[OK] agent-app-server's public API matches {DUMP.relative_to(ANDROID)}.")
        return 0
    diff = difflib.unified_diff(saved.splitlines(), current.splitlines(), "saved", "current", lineterm="", n=0)
    print("[FAIL] agent-app-server's public API changed.\n"
          "What: declarations a host may use were added, removed or changed (below).\n"
          "Why: the app server is meant to move to the runtime's repository; its API only grows on purpose.\n"
          "How: make new things `internal` (or @InternalAppServerApi for other modules' tests), or, when hosts do "
          "need it, run `python workspace/apps/android/tools/app-server-api/api_dump.py --write` and commit the dump.")
    print("\n".join(list(diff)[2:200]))
    return 1


if __name__ == "__main__":
    sys.exit(main())
