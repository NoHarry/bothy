# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Normalize agent-tool hook events into Harness trace events."""

import argparse
import json
import re
from datetime import datetime
from pathlib import Path

from harness_runtime import ROOT, display_path, hook_event_path, load_yaml


EVENTS = {
    "session_start",
    "user_prompt_submit",
    "pre_tool_use",
    "post_tool_use",
    "permission_request",
    "stop",
    "pre_compact",
    "post_compact",
    "session_idle",
}

DANGEROUS_COMMANDS = [
    re.compile(r"\bgit\s+reset\s+--hard\b", re.I),
    re.compile(r"\bgit\s+checkout\s+--\b", re.I),
    re.compile(r"\brm\s+-rf\b", re.I),
    re.compile(r"\bRemove-Item\b.*\s-Recurse\b", re.I),
]


def parse_payload(args):
    if args.payload_file:
        text = Path(args.payload_file).read_text(encoding="utf-8")
    else:
        text = args.payload or "{}"
    try:
        data = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"payload must be JSON: {error}") from error
    if not isinstance(data, dict):
        raise ValueError("payload must be a JSON object")
    return data


def load_policy():
    for name in ("harness/policy.yaml", "harness/policy.example.yaml"):
        path = ROOT / name
        if path.exists():
            return load_yaml(path)
    return {}


def find_command(payload):
    for key in ("command", "cmd", "shell", "input"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value
    tool = payload.get("tool")
    if isinstance(tool, dict):
        for key in ("command", "input"):
            value = tool.get(key)
            if isinstance(value, str) and value.strip():
                return value
    return ""


def evaluate(event, payload, policy):
    observations = []
    command = find_command(payload)
    if event in {"pre_tool_use", "permission_request"} and command:
        for pattern in DANGEROUS_COMMANDS:
            if pattern.search(command):
                observations.append(
                    {
                        "level": "warn",
                        "code": "dangerous_command",
                        "message": "Command matches a dangerous-operation pattern.",
                    }
                )
                break
    hook_policy = policy.get("hook_bus") if isinstance(policy, dict) else {}
    mode = hook_policy.get("mode", "dry-run") if isinstance(hook_policy, dict) else "dry-run"
    observations.append({"level": "info", "code": "mode", "message": f"policy mode: {mode}"})
    return observations


def event_record(event, tool, payload, observations):
    return {
        "timestamp": datetime.now().isoformat(timespec="seconds"),
        "event": event,
        "tool": tool,
        "payload": payload,
        "observations": observations,
    }


def append_event(record):
    path = hook_event_path(ROOT, f"{datetime.now().date().isoformat()}.jsonl")
    path.parent.mkdir(parents=True, exist_ok=True)
    existing = path.read_text(encoding="utf-8") if path.exists() else ""
    line = json.dumps(record, ensure_ascii=False, sort_keys=True)
    path.write_text(existing + line + "\n", encoding="utf-8")
    return path


def parse_args():
    parser = argparse.ArgumentParser(description="Normalize a tool hook event for Harness.")
    _ = parser.add_argument("--event", required=True, choices=sorted(EVENTS))
    _ = parser.add_argument("--tool", default="unknown", help="codex | claude | opencode | unknown")
    _ = parser.add_argument("--payload", default="{}", help="JSON object payload.")
    _ = parser.add_argument("--payload-file", help="Read JSON payload from file.")
    _ = parser.add_argument("--dry-run", action="store_true", help="Evaluate without writing an event.")
    _ = parser.add_argument("--json", action="store_true", help="Print machine-readable output.")
    return parser.parse_args()


def main():
    args = parse_args()
    try:
        payload = parse_payload(args)
    except ValueError as error:
        print(f"[FAIL] {error}")
        return 1
    policy = load_policy()
    observations = evaluate(args.event, payload, policy)
    record = event_record(args.event, args.tool, payload, observations)
    path = None if args.dry_run else append_event(record)
    output = {"record": record, "dry_run": args.dry_run, "path": display_path(ROOT, path) if path else ""}
    if args.json:
        print(json.dumps(output, ensure_ascii=False, indent=2))
    else:
        print("[DRY-RUN]" if args.dry_run else "[RECORDED]", args.event)
        if path:
            print(f"[PATH] {display_path(ROOT, path)}")
        for item in observations:
            print(f"[{item['level'].upper()}] {item['code']}: {item['message']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
