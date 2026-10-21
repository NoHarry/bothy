# pyright: reportUnknownParameterType=false, reportMissingParameterType=false, reportUnknownVariableType=false, reportUnknownArgumentType=false, reportAny=false
"""Manage local Harness agent runtime records under harness/.local."""

import argparse
import json
import shutil
import subprocess
from datetime import datetime
from pathlib import Path

from harness_runtime import ROOT, load_yaml, trace_path


RUNTIME_CONTRACT_VERSION = "1.1.0"
SCHEMA_VERSION = 1
RUNTIME_ROOT = ROOT / "harness" / ".local" / "agent-runtime"
CURRENT_DIR = RUNTIME_ROOT / "current"
RUNS_DIR = RUNTIME_ROOT / "runs"
ARCHIVE_DIR = RUNTIME_ROOT / "archive"
MANIFEST_PATH = RUNTIME_ROOT / "manifest.json"
CURRENT_PATH = CURRENT_DIR / "run.json"
VALID_STATUSES = {
    "created",
    "running",
    "planned",
    "paused",
    "blocked",
    "needs_review",
    "completed",
    "failed",
    "cancelled",
    "archived",
}
RESUMABLE_STATUSES = {"running", "planned", "paused", "blocked", "needs_review"}
LIGHTWEIGHT_TASK_TYPES = {"plan", "research", "review"}
VALID_BLOCKERS = {
    "BLOCKED_REQUIREMENT_AMBIGUITY",
    "BLOCKED_OWNER_DECISION",
    "BLOCKED_ENVIRONMENT",
    "BLOCKED_VALIDATION",
    "BLOCKED_CONFLICT",
    "BLOCKED_PERMISSION",
    "BLOCKED_DEPENDENCY",
    "BLOCKED_SCOPE",
}
VALID_CHECK_STATUS = {"pass", "fail", "skip"}
VALID_REQUEST_STATUSES = {"pending", "approved", "rejected", "cancelled"}
OPEN_REQUEST_STATUSES = {"pending"}
COMPLETION_BAR = [
    ("plan-updated", "Plan updated"),
    ("reflection-recorded", "First-principles reflection recorded"),
    ("acceptance-recorded", "Acceptance criteria satisfied or exceptions recorded"),
    ("required-validation", "Required validation executed or skip reason recorded"),
    ("runtime-validation", "Runtime validation evidence recorded"),
    ("handoff-updated", "Handoff updated"),
]


def now():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def today():
    return datetime.now().date().isoformat()


def safe_slug(text, fallback="task"):
    allowed = []
    for char in str(text).lower():
        if char.isalnum():
            allowed.append(char)
        elif char in {" ", "-", "_", ".", "/"}:
            allowed.append("-")
    slug = "-".join(part for part in "".join(allowed).split("-") if part)
    return (slug or fallback)[:60]


def rel(path):
    try:
        return Path(path).resolve().relative_to(ROOT.resolve()).as_posix()
    except ValueError:
        return Path(path).as_posix()


def ensure_dirs():
    for path in (RUNTIME_ROOT, CURRENT_DIR, RUNS_DIR, ARCHIVE_DIR):
        path.mkdir(parents=True, exist_ok=True)


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def read_json(path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def yaml_scalar(value):
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, (int, float)):
        return str(value)
    return json.dumps(str(value), ensure_ascii=False)


def dump_yaml(data, indent=0):
    lines = []
    pad = " " * indent
    if isinstance(data, dict):
        for key, value in data.items():
            if isinstance(value, list) and not value:
                lines.append(f"{pad}{key}: []")
            elif isinstance(value, dict) and not value:
                lines.append(f"{pad}{key}: {{}}")
            if isinstance(value, (dict, list)):
                if value:
                    lines.append(f"{pad}{key}:")
                    lines.extend(dump_yaml(value, indent + 2))
            else:
                lines.append(f"{pad}{key}: {yaml_scalar(value)}")
        return lines
    if isinstance(data, list):
        if not data:
            lines.append(f"{pad}[]")
            return lines
        for item in data:
            if isinstance(item, dict):
                if not item:
                    lines.append(f"{pad}- {{}}")
                    continue
                first = True
                for key, value in item.items():
                    prefix = f"{pad}- " if first else " " * (indent + 2)
                    first = False
                    if isinstance(value, (dict, list)):
                        lines.append(f"{prefix}{key}:")
                        lines.extend(dump_yaml(value, indent + 4))
                    else:
                        lines.append(f"{prefix}{key}: {yaml_scalar(value)}")
            elif isinstance(item, list):
                lines.append(f"{pad}-")
                lines.extend(dump_yaml(item, indent + 2))
            else:
                lines.append(f"{pad}- {yaml_scalar(item)}")
        return lines
    return [f"{pad}{yaml_scalar(data)}"]


def write_yaml(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(dump_yaml(data)) + "\n", encoding="utf-8")


def append_event(run_dir, event_type, message, phase="", extra=None):
    event = {
        "time": now(),
        "type": event_type,
        "phase": phase,
        "message": message,
    }
    if extra:
        event.update(extra)
    path = run_dir / "events.jsonl"
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(event, ensure_ascii=False) + "\n")
    update_state(run_dir, last_event=message, phase=phase or None)
    return event


def read_events(run_dir, limit=20):
    path = run_dir / "events.jsonl"
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError:
            rows.append({"time": "", "type": "raw", "message": line})
    return rows[-limit:] if limit else rows


def requests_path(run_dir):
    return run_dir / "requests.jsonl"


def read_requests(run_dir, status="", request_type="", limit=0):
    path = requests_path(run_dir)
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        if not line.strip():
            continue
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            item = {"request_id": "", "status": "invalid", "raw": line}
        if status and item.get("status") != status:
            continue
        if request_type and item.get("type") != request_type:
            continue
        rows.append(item)
    return rows[-limit:] if limit else rows


def write_requests(run_dir, items):
    path = requests_path(run_dir)
    lines = [json.dumps(item, ensure_ascii=False, sort_keys=True) for item in items]
    path.write_text(("\n".join(lines) + "\n") if lines else "", encoding="utf-8")


def create_request_id(run_dir):
    existing = {item.get("request_id") for item in read_requests(run_dir)}
    base = datetime.now().strftime("req-%Y%m%d-%H%M%S")
    if base not in existing:
        return base
    for index in range(1, 1000):
        candidate = f"{base}-{index:03d}"
        if candidate not in existing:
            return candidate
    raise RuntimeError("Could not allocate a unique request id.")


def read_payload_text(payload="", payload_file=""):
    if payload_file:
        path = Path(payload_file)
        if not path.is_absolute():
            path = ROOT / path
        if not path.exists():
            raise RuntimeError(f"payload file does not exist: {payload_file}")
        return path.read_text(encoding="utf-8", errors="ignore")
    return payload or ""


def pending_requests(run_dir):
    return [
        item
        for item in read_requests(run_dir)
        if item.get("status") in OPEN_REQUEST_STATUSES
    ]


def request_brief(run_dir, limit=8):
    items = read_requests(run_dir, limit=limit)
    if not items:
        return ["- none recorded"]
    lines = []
    for item in items:
        request_id = item.get("request_id", "")
        request_type = item.get("type", "")
        status = item.get("status", "unknown")
        target = item.get("target", "")
        summary = item.get("summary", "")
        suffix = f" - {summary}" if summary else ""
        target_text = f" -> {target}" if target else ""
        lines.append(f"- {status}: `{request_id}` {request_type}{target_text}{suffix}")
    return lines


def git(args):
    result = subprocess.run(
        ["git", *args],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return result


def changed_files():
    result = git(["status", "--short"])
    if result.returncode != 0:
        return []
    files = []
    for line in result.stdout.splitlines():
        path = line[3:].strip()
        if path:
            files.append(path.replace("\\", "/"))
    return sorted(files)


def git_head():
    result = git(["rev-parse", "--short", "HEAD"])
    return result.stdout.strip() if result.returncode == 0 else ""


def git_branch():
    result = git(["branch", "--show-current"])
    return result.stdout.strip() if result.returncode == 0 else ""


def manifest():
    return {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "runtime_root": rel(RUNTIME_ROOT),
        "current_file": rel(CURRENT_PATH),
        "runs_dir": rel(RUNS_DIR),
        "archive_dir": rel(ARCHIVE_DIR),
        "updated_at": now(),
    }


def init_runtime(_args):
    ensure_dirs()
    write_json(MANIFEST_PATH, manifest())
    print(f"[OK] Agent runtime initialized at {rel(RUNTIME_ROOT)}")
    return 0


def current_pointer():
    pointer = read_json(CURRENT_PATH, {})
    if not isinstance(pointer, dict) or not pointer.get("run_id"):
        return {}
    return pointer


def resolve_run_dir(run_id=""):
    if run_id:
        run_dir = RUNS_DIR / run_id
    else:
        pointer = current_pointer()
        run_dir = ROOT / str(pointer.get("run_dir") or "")
        if not pointer or not run_dir.exists():
            raise RuntimeError("No current run. Start one with agent_runtime.py start.")
    if not run_dir.exists():
        raise RuntimeError(f"Run not found: {run_id or rel(run_dir)}")
    return run_dir


def default_task(task_id, objective, args):
    task_type = args.task_type or "implementation"
    required_validation = [] if task_type in LIGHTWEIGHT_TASK_TYPES else ["python scripts/validate.py --dry-run"]
    return {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "task_id": task_id,
        "task_type": task_type,
        "parent_run_id": args.parent_run_id,
        "assigned_by": args.assigned_by,
        "role": args.role,
        "worker_id": args.worker_id,
        "mode": args.mode,
        "attempt": args.attempt,
        "retry_of": args.retry_of,
        "objective": objective,
        "workspace": args.workspace,
        "branch": args.branch,
        "scope": {
            "allowed_paths": [],
            "forbidden_paths": ["harness/.local/**"],
            "owned_paths": [],
            "shared_paths": [],
        },
        "acceptance": [],
        "required_validation": required_validation,
        "memory_policy": {"durable_write": False, "candidate_write": True},
        "eval_policy": {"repo_eval_write": False, "candidate_write": True},
        "handoff_policy": {
            "auto_create": True,
            "auto_update_on_pause": True,
            "handoff_to": "any",
        },
        "handoff_required": True,
    }


def create_run_id(objective):
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    return f"run-{stamp}-{safe_slug(objective)}"


def load_task(args):
    if args.task:
        task_path = Path(args.task)
        if not task_path.is_absolute():
            task_path = ROOT / task_path
        data = load_yaml(task_path)
        if not data:
            raise RuntimeError(f"Task file is empty or invalid: {task_path}")
        text = task_path.read_text(encoding="utf-8")
        objective = str(data.get("objective") or task_path.stem)
        task_id = str(data.get("task_id") or f"local-{datetime.now().strftime('%Y%m%d-%H%M%S')}")
        return data, text, task_id, objective
    objective = args.objective or "Local Harness task"
    task_id = f"local-{datetime.now().strftime('%Y%m%d-%H%M%S')}-{safe_slug(objective)}"
    data = default_task(task_id, objective, args)
    return data, "\n".join(dump_yaml(data)) + "\n", task_id, objective


def create_initial_files(run_dir, run_id, task_id, task_text, task_data, objective):
    run_meta = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "run_id": run_id,
        "task_id": task_id,
        "run_dir": rel(run_dir),
        "created_at": now(),
    }
    state = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "run_id": run_id,
        "task_id": task_id,
        "status": "running",
        "phase": "planning",
        "mode": task_data.get("mode") or "local",
        "assigned_by": task_data.get("assigned_by") or "local",
        "role": task_data.get("role") or "executor",
        "task_type": task_data.get("task_type") or "implementation",
        "worker_id": task_data.get("worker_id"),
        "workspace": task_data.get("workspace") or ".",
        "branch": task_data.get("branch"),
        "updated_at": now(),
        "changed_files": changed_files(),
        "last_event": "started",
    }
    write_json(run_dir / "run.json", run_meta)
    (run_dir / "task.yaml").write_text(task_text, encoding="utf-8")
    write_json(run_dir / "state.json", state)
    write_json(run_dir / "validation.json", {"schema_version": 1, "items": []})
    write_json(run_dir / "eval.json", {"schema_version": 1, "checks": [], "overall_status": "unknown"})
    requests_path(run_dir).write_text("", encoding="utf-8")
    write_yaml(run_dir / "memory-candidates.yaml", {"schema_version": 1, "candidates": []})
    write_yaml(run_dir / "eval-candidates.yaml", {"schema_version": 1, "candidates": []})
    (run_dir / "blockers").mkdir(parents=True, exist_ok=True)
    (run_dir / "artifacts").mkdir(parents=True, exist_ok=True)
    append_event(run_dir, "start", f"Task started: {objective}", "planning")
    state = read_json(run_dir / "state.json", {}) or state
    handoff_data = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "kind": "continuation",
        "handoff_to": "any",
        "role": state.get("role") or "executor",
        "summary": f"Started task: {objective}",
        "done": [],
        "next": ["Continue from the objective and recent events."],
        "context": [],
        "relevant_files": [],
        "tried": [],
        "decisions": [],
        "constraints": [],
        "open_questions": [],
        "risks": [],
        "do_not_repeat": [],
        "evidence": [],
        "updated_at": now(),
    }
    write_json(run_dir / "handoff.json", handoff_data)
    (run_dir / "handoff.md").write_text(
        render_handoff(run_dir, state, task_data, handoff_data),
        encoding="utf-8",
    )


def start_run(args):
    ensure_dirs()
    task_data, task_text, task_id, objective = load_task(args)
    run_id = args.run_id or create_run_id(objective)
    run_dir = RUNS_DIR / run_id
    if run_dir.exists():
        raise RuntimeError(f"Run already exists: {run_id}")
    run_dir.mkdir(parents=True)
    create_initial_files(run_dir, run_id, task_id, task_text, task_data, objective)
    write_json(
        CURRENT_PATH,
        {
            "schema_version": SCHEMA_VERSION,
            "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
            "run_id": run_id,
            "run_dir": rel(run_dir),
            "updated_at": now(),
        },
    )
    write_json(MANIFEST_PATH, manifest())
    output = {"run_id": run_id, "run_dir": rel(run_dir), "task_id": task_id}
    print(json.dumps(output, ensure_ascii=False, indent=2) if args.json else f"[OK] Started {run_id}")
    return 0


def update_state(run_dir, **updates):
    path = run_dir / "state.json"
    state = read_json(path, {})
    if not isinstance(state, dict):
        state = {}
    for key, value in updates.items():
        if value is not None:
            state[key] = value
    state["updated_at"] = now()
    state["changed_files"] = changed_files()
    write_json(path, state)
    return state


def run_summary(run_dir):
    state = read_json(run_dir / "state.json", {}) or {}
    return {
        "run_id": state.get("run_id") or run_dir.name,
        "task_id": state.get("task_id"),
        "status": state.get("status", "unknown"),
        "phase": state.get("phase", ""),
        "updated_at": state.get("updated_at", ""),
        "run_dir": rel(run_dir),
    }


def list_runs(args):
    ensure_dirs()
    runs = [run_summary(path) for path in sorted(RUNS_DIR.iterdir()) if path.is_dir()]
    if args.status:
        runs = [item for item in runs if item.get("status") == args.status]
    print(json.dumps({"runs": runs}, ensure_ascii=False, indent=2) if args.json else "\n".join(item["run_id"] for item in runs))
    return 0


def show_run(args):
    run_dir = resolve_run_dir(args.run_id)
    workpad_json = ensure_workpad(run_dir)
    workpad = (run_dir / "workpad.md").read_text(encoding="utf-8", errors="ignore") if (run_dir / "workpad.md").exists() else ""
    data = {
        "run": read_json(run_dir / "run.json", {}),
        "state": read_json(run_dir / "state.json", {}),
        "task": load_yaml(run_dir / "task.yaml"),
        "validation": read_json(run_dir / "validation.json", {}),
        "eval": read_json(run_dir / "eval.json", {}),
        "requests": read_requests(run_dir),
        "handoff_json": read_json(run_dir / "handoff.json", {}),
        "workpad_json": workpad_json,
        "workpad": workpad,
        "events": read_events(run_dir, args.events),
        "blockers": load_blockers(run_dir),
        "run_dir": rel(run_dir),
    }
    print(json.dumps(data, ensure_ascii=False, indent=2))
    return 0


def current_run(args):
    pointer = current_pointer()
    if not pointer:
        print(json.dumps({"current": None}, ensure_ascii=False, indent=2) if args.json else "[INFO] No current run.")
        return 2
    if args.json:
        run_dir = ROOT / str(pointer["run_dir"])
        data = {"current": pointer, "state": read_json(run_dir / "state.json", {})}
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(pointer["run_id"])
    return 0


def discover(args):
    ensure_dirs()
    write_json(MANIFEST_PATH, manifest())
    data = read_json(MANIFEST_PATH, {})
    print(json.dumps(data, ensure_ascii=False, indent=2) if args.json else rel(MANIFEST_PATH))
    return 0


def status(args):
    ensure_dirs()
    counts = {}
    for path in sorted(RUNS_DIR.iterdir()):
        if not path.is_dir():
            continue
        state = read_json(path / "state.json", {}) or {}
        key = str(state.get("status") or "unknown")
        counts[key] = counts.get(key, 0) + 1
    data = {
        "runtime_initialized": MANIFEST_PATH.exists(),
        "current_run": current_pointer().get("run_id"),
        "runs": counts,
        "last_updated_at": now(),
    }
    print(json.dumps(data, ensure_ascii=False, indent=2) if args.json else json.dumps(data, ensure_ascii=False))
    return 0


def heartbeat(args):
    run_dir = resolve_run_dir(args.run_id)
    update_state(run_dir, status="running", phase=args.phase, last_event="heartbeat")
    append_event(run_dir, "heartbeat", f"Heartbeat: {args.phase}", args.phase)
    print("[OK] heartbeat recorded")
    return 0


def event(args):
    run_dir = resolve_run_dir(args.run_id)
    append_event(run_dir, args.type, args.message, args.phase)
    print("[OK] event recorded")
    return 0


def validation(args):
    if args.status not in VALID_CHECK_STATUS:
        raise RuntimeError(f"validation status must be one of {sorted(VALID_CHECK_STATUS)}")
    run_dir = resolve_run_dir(args.run_id)
    data = read_json(run_dir / "validation.json", {"schema_version": 1, "items": []})
    item = {
        "time": now(),
        "command": args.validation_command,
        "status": args.status,
        "summary": args.summary or "",
        "evidence": args.evidence or [],
    }
    data.setdefault("items", []).append(item)
    write_json(run_dir / "validation.json", data)
    append_event(
        run_dir,
        "validation",
        f"{args.status}: {args.validation_command}",
        extra={"command": args.validation_command, "status": args.status},
    )
    print("[OK] validation recorded")
    return 0


def eval_check(args):
    if args.status not in VALID_CHECK_STATUS:
        raise RuntimeError(f"eval status must be one of {sorted(VALID_CHECK_STATUS)}")
    run_dir = resolve_run_dir(args.run_id)
    data = read_json(run_dir / "eval.json", {"schema_version": 1, "checks": []})
    check = {
        "id": args.id,
        "type": args.type,
        "status": args.status,
        "summary": args.summary or "",
        "time": now(),
        "evidence": args.evidence or [],
    }
    data.setdefault("checks", []).append(check)
    statuses = [item.get("status") for item in data.get("checks", [])]
    data["overall_status"] = "fail" if "fail" in statuses else "pass" if statuses else "unknown"
    write_json(run_dir / "eval.json", data)
    append_event(run_dir, "eval", f"{args.status}: {args.id}", extra={"eval_id": args.id, "status": args.status})
    print("[OK] eval recorded")
    return 0


def block(args):
    if args.type not in VALID_BLOCKERS:
        raise RuntimeError(f"blocker type must be one of {sorted(VALID_BLOCKERS)}")
    run_dir = resolve_run_dir(args.run_id)
    blockers_dir = run_dir / "blockers"
    blockers_dir.mkdir(parents=True, exist_ok=True)
    blocker_id = args.blocker_id or f"blk-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    data = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "blocker_id": blocker_id,
        "type": args.type,
        "severity": args.severity,
        "message": args.message,
        "evidence": args.evidence or [],
        "needed_from": args.needed_from,
        "suggested_next_action": args.suggested_next_action or "",
        "status": "open",
        "created_at": now(),
    }
    write_yaml(blockers_dir / f"{blocker_id}.yaml", data)
    update_state(run_dir, status="blocked", phase="blocked", last_event=args.message)
    append_event(run_dir, "blocker", args.message, "blocked", {"blocker_id": blocker_id, "blocker_type": args.type})
    print(f"[OK] blocker recorded: {blocker_id}")
    return 0


def protocol_request(args):
    run_dir = resolve_run_dir(args.run_id)
    state = read_json(run_dir / "state.json", {}) or {}
    request_id = str(args.request_id or create_request_id(run_dir)).strip()
    if not request_id:
        raise RuntimeError("request id is required")
    if any(item.get("request_id") == request_id for item in read_requests(run_dir)):
        raise RuntimeError(f"request already exists: {request_id}")
    request_type = str(args.request_type or "").strip()
    if not request_type:
        raise RuntimeError("request type is required")
    target = str(args.target or "").strip()
    if not target:
        raise RuntimeError("request target is required")
    created_at = now()
    item = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "request_id": request_id,
        "type": request_type,
        "sender": args.sender or state.get("role") or "local",
        "target": target,
        "status": "pending",
        "summary": args.summary or "",
        "payload": read_payload_text(args.payload, args.payload_file),
        "evidence": args.evidence or [],
        "response": None,
        "feedback": "",
        "created_at": created_at,
        "updated_at": created_at,
        "resolved_at": "",
    }
    items = read_requests(run_dir)
    items.append(item)
    write_requests(run_dir, items)
    append_event(
        run_dir,
        "request",
        f"pending {request_type}: {request_id}",
        "request",
        {"request_id": request_id, "request_type": request_type, "target": target},
    )
    output = {"request": item, "requests_path": rel(requests_path(run_dir))}
    if args.json:
        print(json.dumps(output, ensure_ascii=False, indent=2))
    else:
        print(f"[OK] request recorded: {request_id}")
    return 0


def respond_request(args):
    if args.status not in VALID_REQUEST_STATUSES - {"pending"}:
        raise RuntimeError(
            f"response status must be one of {sorted(VALID_REQUEST_STATUSES - {'pending'})}"
        )
    request_id = str(args.request_id or "").strip()
    if not request_id:
        raise RuntimeError("request id is required")
    run_dir = resolve_run_dir(args.run_id)
    state = read_json(run_dir / "state.json", {}) or {}
    items = read_requests(run_dir)
    matched = None
    for item in items:
        if item.get("request_id") == request_id:
            matched = item
            break
    if matched is None:
        raise RuntimeError(f"request not found: {request_id}")
    if matched.get("status") not in OPEN_REQUEST_STATUSES and not args.force:
        raise RuntimeError(
            f"request {request_id} is already {matched.get('status')}; use --force to overwrite"
        )
    resolved_at = now()
    matched["status"] = args.status
    matched["response"] = {
        "responder": args.responder or state.get("role") or "local",
        "status": args.status,
        "feedback": args.feedback or "",
        "evidence": args.evidence or [],
        "time": resolved_at,
    }
    matched["feedback"] = args.feedback or ""
    matched["updated_at"] = resolved_at
    matched["resolved_at"] = resolved_at
    write_requests(run_dir, items)
    append_event(
        run_dir,
        "request_response",
        f"{args.status}: {request_id}",
        "request",
        {"request_id": request_id, "status": args.status},
    )
    output = {"request": matched, "requests_path": rel(requests_path(run_dir))}
    if args.json:
        print(json.dumps(output, ensure_ascii=False, indent=2))
    else:
        print(f"[OK] request {request_id} marked {args.status}")
    return 0


def list_protocol_requests(args):
    run_dir = resolve_run_dir(args.run_id)
    items = read_requests(
        run_dir,
        status=args.status or "",
        request_type=args.request_type or "",
        limit=args.limit,
    )
    if args.json:
        print(
            json.dumps(
                {"requests": items, "requests_path": rel(requests_path(run_dir))},
                ensure_ascii=False,
                indent=2,
            )
        )
        return 0
    if not items:
        print("[OK] No protocol requests.")
        return 0
    for item in items:
        request_id = item.get("request_id", "")
        request_type = item.get("type", "")
        status = item.get("status", "unknown")
        target = item.get("target", "")
        summary = item.get("summary", "")
        suffix = f" - {summary}" if summary else ""
        print(f"{request_id} [{status}] {request_type} -> {target}{suffix}")
    return 0


def load_blockers(run_dir):
    blockers = []
    for path in sorted((run_dir / "blockers").glob("*.yaml")):
        data = load_yaml(path)
        if data:
            blockers.append(data)
    return blockers


def result_skeleton(state, task):
    return "\n".join(
        [
            "# Agent Result",
            "",
            f"Status: {state.get('status', 'completed')}",
            f"Run: {state.get('run_id')}",
            f"Task: {state.get('task_id')}",
            "",
            "## Summary",
            str(task.get("objective") or "No summary recorded."),
            "",
            "## Changed Files",
            *[f"- {path}" for path in state.get("changed_files", [])],
            "",
            "## Validation",
            "- See validation.json and eval.json.",
            "",
            "## Risks",
            "- Review generated result before treating this task as accepted.",
            "",
            "## Follow-ups",
            "- none recorded",
            "",
        ]
    )


def completion_items():
    return [{"id": item_id, "label": label, "done": False, "note": ""} for item_id, label in COMPLETION_BAR]


def workpad_item(text, done=False):
    return {"text": str(text).strip(), "done": done, "time": now()}


def default_workpad(run_dir, state, task):
    return {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "run_id": state.get("run_id") or run_dir.name,
        "task_id": state.get("task_id"),
        "updated_at": now(),
        "environment": {
            "workspace": rel(ROOT),
            "branch": state.get("branch") or git_branch(),
            "head": git_head(),
            "run_dir": rel(run_dir),
        },
        "completion_bar": completion_items(),
        "plan": [],
        "reflections": [],
        "acceptance": [],
        "validation": [],
        "notes": [],
        "confusions": [],
    }


def normalize_workpad(run_dir, state, task, data):
    if not isinstance(data, dict):
        data = default_workpad(run_dir, state, task)
    data.setdefault("schema_version", SCHEMA_VERSION)
    data.setdefault("runtime_contract_version", RUNTIME_CONTRACT_VERSION)
    data.setdefault("run_id", state.get("run_id") or run_dir.name)
    data.setdefault("task_id", state.get("task_id"))
    data.setdefault("updated_at", now())
    data.setdefault("environment", {})
    data["environment"].setdefault("workspace", rel(ROOT))
    data["environment"].setdefault("branch", state.get("branch") or git_branch())
    data["environment"].setdefault("head", git_head())
    data["environment"].setdefault("run_dir", rel(run_dir))
    existing_completion = {
        str(item.get("id")): item
        for item in data.get("completion_bar", [])
        if isinstance(item, dict) and item.get("id")
    }
    data["completion_bar"] = []
    for item_id, label in COMPLETION_BAR:
        item = existing_completion.get(item_id, {})
        data["completion_bar"].append(
            {
                "id": item_id,
                "label": item.get("label") or label,
                "done": bool(item.get("done")),
                "note": item.get("note") or "",
            }
        )
    for key in ("plan", "reflections", "acceptance", "validation", "notes", "confusions"):
        values = data.get(key)
        data[key] = values if isinstance(values, list) else []
    refresh_workpad_inferred_state(run_dir, task, data)
    return data


def refresh_workpad_inferred_state(run_dir, task, data):
    validation_data = read_json(run_dir / "validation.json", {}) or {}
    validation_items = validation_data.get("items") if isinstance(validation_data, dict) else []
    latest_validation = {}
    for item in validation_items or []:
        command = str(item.get("command") or "")
        if command:
            latest_validation[command] = item
    for item in data.get("validation", []):
        command = str(item.get("text") or item.get("command") or "")
        match = latest_validation.get(command)
        if match and match.get("status") in {"pass", "skip"}:
            item["done"] = True
            item["note"] = match.get("summary") or match.get("status")
    completion = {item["id"]: item for item in data.get("completion_bar", [])}
    if data.get("plan"):
        completion["plan-updated"]["done"] = True
    if data.get("reflections"):
        completion["reflection-recorded"]["done"] = True
    required = clean_items(task.get("required_validation"))
    if not required:
        completion["required-validation"]["done"] = True
        completion["required-validation"]["note"] = "no required validation"
    elif all((latest_validation.get(command) or {}).get("status") in {"pass", "skip"} for command in required):
        completion["required-validation"]["done"] = True
    if validation_items:
        completion["runtime-validation"]["done"] = True
    handoff_data = read_json(run_dir / "handoff.json", {}) or {}
    if handoff_data.get("summary"):
        completion["handoff-updated"]["done"] = True


def write_workpad(run_dir, data):
    state = read_json(run_dir / "state.json", {}) or {}
    task = load_yaml(run_dir / "task.yaml")
    data = normalize_workpad(run_dir, state, task, data)
    data["updated_at"] = now()
    write_json(run_dir / "workpad.json", data)
    (run_dir / "workpad.md").write_text(render_workpad(run_dir, data), encoding="utf-8")
    return data


def ensure_workpad(run_dir):
    state = read_json(run_dir / "state.json", {}) or {}
    task = load_yaml(run_dir / "task.yaml")
    data = read_json(run_dir / "workpad.json", None)
    if data is None:
        data = default_workpad(run_dir, state, task)
    return write_workpad(run_dir, data)


def render_checkbox(item):
    mark = "x" if item.get("done") else " "
    note = item.get("note") or ""
    suffix = f" - {note}" if note else ""
    text = item.get("label") or item.get("text") or ""
    return f"- [{mark}] {text}{suffix}"


def render_workpad(_run_dir, data):
    env = data.get("environment") or {}
    lines = [
        "# Agent Workpad",
        "",
        "## Environment",
        f"- Workspace: {env.get('workspace', '')}",
        f"- Branch: {env.get('branch', '')}",
        f"- HEAD: {env.get('head', '')}",
        f"- Run: {data.get('run_id', '')}",
        f"- Run Dir: {env.get('run_dir', '')}",
        f"- Updated: {data.get('updated_at', '')}",
        "",
        "## Completion Bar",
        *[render_checkbox(item) for item in data.get("completion_bar", [])],
        "",
        "## Plan",
        *render_workpad_section(data.get("plan")),
        "",
        "## Reflection",
        *render_note_section(data.get("reflections")),
        "",
        "## Acceptance Criteria",
        *render_workpad_section(data.get("acceptance")),
        "",
        "## Validation",
        *render_workpad_section(data.get("validation")),
        "",
        "## Notes",
        *render_note_section(data.get("notes")),
        "",
        "## Confusions",
        *render_note_section(data.get("confusions")),
        "",
    ]
    return "\n".join(lines)


def render_workpad_section(items):
    values = [item for item in items or [] if isinstance(item, dict)]
    if not values:
        return ["- [ ] none recorded"]
    return [render_checkbox(item) for item in values]


def render_note_section(items):
    values = [item for item in items or [] if isinstance(item, dict) and item.get("text")]
    if not values:
        return ["- none recorded"]
    return [f"- {item.get('text')}" for item in values]


def pending_items(items, limit=3):
    rows = []
    for item in items or []:
        if isinstance(item, dict) and not item.get("done") and item.get("text"):
            rows.append(str(item.get("text")))
    return rows[:limit]


def pending_completion(workpad, limit=5):
    rows = []
    for item in workpad.get("completion_bar", []):
        if isinstance(item, dict) and not item.get("done"):
            rows.append(item.get("label") or item.get("id"))
    return rows[:limit]


def workpad_summary(run_dir):
    workpad = ensure_workpad(run_dir)
    pending_bar = pending_completion(workpad, 5)
    return {
        "workpad_path": rel(run_dir / "workpad.md"),
        "pending_completion_count": len(pending_completion(workpad, 100)),
        "pending_completion": pending_bar,
        "pending_plan": pending_items(workpad.get("plan")),
        "recent_reflections": [item.get("text") for item in workpad.get("reflections", [])[-3:] if isinstance(item, dict)],
        "pending_validation": pending_items(workpad.get("validation")),
        "open_confusions": [item.get("text") for item in workpad.get("confusions", [])[:3] if isinstance(item, dict)],
    }


def clean_items(items):
    if items is None:
        return []
    if isinstance(items, str):
        text = items.strip()
        if text in {"", "[]", "{}", "null", "None"}:
            return []
        return [text]
    if not isinstance(items, list):
        items = [items]
    return [str(item).strip() for item in items if str(item).strip()]


def unique_clean_items(*groups):
    values = []
    seen = set()
    for group in groups:
        for item in clean_items(group):
            if item in seen:
                continue
            values.append(item)
            seen.add(item)
    return values


def markdown_list(items, empty="- none recorded"):
    values = clean_items(items)
    if not values:
        return [empty]
    return [f"- {item}" for item in values]


def scope_brief(task, handoff):
    scope = task.get("scope") if isinstance(task.get("scope"), dict) else {}
    rows = []
    for key, label in (
        ("allowed_paths", "Allowed paths"),
        ("owned_paths", "Owned paths"),
        ("shared_paths", "Shared paths"),
        ("forbidden_paths", "Forbidden paths"),
    ):
        values = clean_items(scope.get(key))
        if values:
            rows.append(f"- {label}: {', '.join(values)}")
    rows.extend(f"- {item}" for item in clean_items(handoff.get("constraints")))
    return rows or ["- none recorded"]


def acceptance_brief(task, workpad):
    rows = []
    for item in clean_items(task.get("acceptance")):
        rows.append(f"- {item}")
    workpad_rows = render_workpad_section(workpad.get("acceptance"))
    if workpad_rows != ["- [ ] none recorded"]:
        rows.extend(workpad_rows)
    return rows or ["- [ ] none recorded"]


def validation_brief(run_dir):
    data = read_json(run_dir / "validation.json", {}) or {}
    items = data.get("items") if isinstance(data, dict) else []
    if not items:
        return ["- none recorded"]
    lines = []
    for item in items[-5:]:
        status = item.get("status", "unknown")
        command = item.get("command", "")
        summary = item.get("summary", "")
        suffix = f" - {summary}" if summary else ""
        lines.append(f"- {status}: `{command}`{suffix}")
    return lines


def protocol_request_brief(run_dir):
    return request_brief(run_dir)


def recent_event_brief(run_dir):
    events = read_events(run_dir, 8)
    if not events:
        return ["- none recorded"]
    return [
        f"- {item.get('time', '')} [{item.get('type', 'event')}] {item.get('message', '')}"
        for item in events
    ]


def render_handoff(run_dir, state, task, handoff):
    changed = state.get("changed_files") or changed_files()
    objective = task.get("objective") or state.get("task_id") or "No objective recorded."
    workpad_data = ensure_workpad(run_dir)
    workpad = workpad_summary(run_dir)
    next_items = clean_items(handoff.get("next"))
    if not next_items:
        next_items = ["Continue from the objective and recent events."]
    relevant_files = unique_clean_items(handoff.get("relevant_files"), changed)
    return "\n".join(
        [
            "# Agent Handoff",
            "",
            "## Task",
            str(objective),
            "",
            "## Context",
            *markdown_list(handoff.get("context"), "- No additional context recorded."),
            "",
            "## Relevant Files",
            *markdown_list(relevant_files),
            "",
            "## Current State",
            f"- Objective: {objective}",
            f"- Status: {state.get('status', 'unknown')}",
            f"- Phase: {state.get('phase', '')}",
            f"- Kind: {handoff.get('kind', 'continuation')}",
            f"- Role: {handoff.get('role') or state.get('role') or 'executor'}",
            f"- Handoff To: {handoff.get('handoff_to', 'any')}",
            f"- Updated: {handoff.get('updated_at', now())}",
            "",
            "Summary:",
            "",
            handoff.get("summary") or "No summary recorded.",
            "",
            "Done:",
            "",
            *markdown_list(handoff.get("done")),
            "",
            "Next:",
            "",
            *markdown_list(next_items),
            "",
            "## Workpad",
            f"- Path: `{workpad.get('workpad_path')}`",
            f"- Pending Completion: {workpad.get('pending_completion_count', 0)}",
            *markdown_list(workpad.get("pending_plan"), "- pending plan: none recorded"),
            *[f"- pending validation: {item}" for item in workpad.get("pending_validation", [])],
            *[f"- confusion: {item}" for item in workpad.get("open_confusions", [])],
            "",
            "## Protocol Requests",
            *protocol_request_brief(run_dir),
            "",
            "## Reflection",
            *markdown_list(workpad.get("recent_reflections"), "- none recorded"),
            "",
            "## What Was Tried",
            *markdown_list(handoff.get("tried")),
            "",
            "## Decisions",
            *markdown_list(handoff.get("decisions")),
            "",
            "## Acceptance Criteria",
            *acceptance_brief(task, workpad_data),
            "",
            "## Constraints",
            *scope_brief(task, handoff),
            "",
            "## Open Questions",
            *markdown_list(handoff.get("open_questions")),
            "",
            "## Risks",
            *markdown_list(handoff.get("risks")),
            "",
            "## Files Touched",
            *markdown_list(changed),
            "",
            "## Validation Evidence",
            *validation_brief(run_dir),
            "",
            "## Evidence",
            *markdown_list(handoff.get("evidence")),
            "",
            "## Recent Events",
            *recent_event_brief(run_dir),
            "",
            "## Do Not Repeat",
            *markdown_list(handoff.get("do_not_repeat")),
            "",
            "## Resume Commands",
            f"- `python scripts/agent_runtime.py resume --run-id {state.get('run_id') or run_dir.name} --json`",
            f"- `python scripts/agent_runtime.py show --run-id {state.get('run_id') or run_dir.name} --json`",
            f"- `python scripts/agent_runtime.py workpad --run-id {state.get('run_id') or run_dir.name} --note \"...\"`",
            "",
        ]
    )


def handoff_skeleton(state, task):
    handoff = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "kind": "completion",
        "handoff_to": "any",
        "role": state.get("role") or "executor",
        "summary": str(task.get("objective") or "No summary recorded."),
        "done": [],
        "next": ["Review acceptance criteria and result.md."],
        "context": [],
        "relevant_files": [],
        "tried": [],
        "decisions": [],
        "constraints": [],
        "open_questions": [],
        "risks": [],
        "do_not_repeat": [],
        "evidence": [],
        "updated_at": now(),
    }
    run_dir = RUNS_DIR / str(state.get("run_id") or "")
    return render_handoff(run_dir, state, task, handoff)


def handoff_payload(args, state, previous):
    def list_field(arg_name, payload_name):
        value = clean_items(getattr(args, arg_name))
        return value if value else previous.get(payload_name) or []

    summary = args.summary or previous.get("summary") or "No summary recorded."
    return {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "kind": args.kind or previous.get("kind") or "continuation",
        "handoff_to": args.handoff_to or previous.get("handoff_to") or "any",
        "role": args.role or previous.get("role") or state.get("role") or "executor",
        "summary": summary,
        "done": list_field("done", "done"),
        "next": list_field("next", "next"),
        "context": list_field("context", "context"),
        "relevant_files": list_field("relevant_file", "relevant_files"),
        "tried": list_field("tried", "tried"),
        "decisions": list_field("decision", "decisions"),
        "constraints": list_field("constraint", "constraints"),
        "open_questions": list_field("open_question", "open_questions"),
        "risks": list_field("risk", "risks"),
        "do_not_repeat": list_field("do_not_repeat", "do_not_repeat"),
        "evidence": list_field("evidence", "evidence"),
        "updated_at": now(),
    }


def write_handoff(run_dir, args, status=None):
    if status and status not in VALID_STATUSES:
        raise RuntimeError(f"status must be one of {sorted(VALID_STATUSES)}")
    previous = read_json(run_dir / "handoff.json", {}) or {}
    state = read_json(run_dir / "state.json", {}) or {}
    payload = handoff_payload(args, state, previous)
    phase = args.phase or payload["kind"]
    state_updates = {"phase": phase, "last_event": f"handoff: {payload['kind']}"}
    if status:
        state_updates["status"] = status
    update_state(run_dir, **state_updates)
    append_event(
        run_dir,
        "handoff",
        payload["summary"],
        phase,
        {"kind": payload["kind"], "handoff_to": payload["handoff_to"]},
    )
    state = read_json(run_dir / "state.json", {}) or state
    task = load_yaml(run_dir / "task.yaml")
    write_json(run_dir / "handoff.json", payload)
    (run_dir / "handoff.md").write_text(
        render_handoff(run_dir, state, task, payload),
        encoding="utf-8",
    )
    print(f"[OK] handoff recorded: {rel(run_dir / 'handoff.md')}")
    return 0


def handoff(args):
    status = args.status or "paused"
    return write_handoff(resolve_run_dir(args.run_id), args, status)


def pause(args):
    return write_handoff(resolve_run_dir(args.run_id), args, "paused")


def next_runs(args):
    ensure_dirs()
    statuses = None
    if args.status and args.status != "all":
        statuses = {item.strip() for item in args.status.split(",") if item.strip()}
    elif not args.status:
        statuses = RESUMABLE_STATUSES
    runs = []
    for path in sorted(RUNS_DIR.iterdir()):
        if not path.is_dir():
            continue
        state = read_json(path / "state.json", {}) or {}
        status = str(state.get("status") or "unknown")
        if statuses is not None and status not in statuses:
            continue
        task = load_yaml(path / "task.yaml")
        handoff_data = read_json(path / "handoff.json", {}) or {}
        runs.append(
            {
                "run_id": state.get("run_id") or path.name,
                "status": status,
                "phase": state.get("phase", ""),
                "task_type": state.get("task_type", ""),
                "role": state.get("role", ""),
                "updated_at": state.get("updated_at", ""),
                "objective": task.get("objective") or state.get("task_id") or path.name,
                "summary": handoff_data.get("summary", ""),
                "next": clean_items(handoff_data.get("next"))[:3],
                "open_questions": clean_items(handoff_data.get("open_questions"))[:3],
                "workpad": workpad_summary(path),
                "run_dir": rel(path),
                "resume_command": f"python scripts/agent_runtime.py resume --run-id {path.name} --json",
            }
        )
    runs.sort(key=lambda item: item.get("updated_at", ""), reverse=True)
    if args.limit:
        runs = runs[: args.limit]
    if args.json:
        print(json.dumps({"runs": runs}, ensure_ascii=False, indent=2))
        return 0
    if not runs:
        print("[OK] No resumable runs.")
        return 0
    for item in runs:
        print(f"{item['run_id']} [{item['status']}] {item['objective']}")
        for step in item.get("next") or ["Continue from the objective and recent events."]:
            print(f"  - next: {step}")
        workpad = item.get("workpad") or {}
        print(f"  - pending completion: {workpad.get('pending_completion_count', 0)}")
        if workpad.get("pending_validation"):
            print(f"  - pending validation: {workpad['pending_validation'][0]}")
        print(f"  - resume: {item['resume_command']}")
    return 0


def append_workpad_items(data, key, values):
    for value in clean_items(values):
        data.setdefault(key, []).append(workpad_item(value))


def mark_completion(data, item_ids, done):
    wanted = {str(item).strip() for item in item_ids or [] if str(item).strip()}
    if not wanted:
        return []
    changed = []
    valid = {item["id"] for item in data.get("completion_bar", [])}
    unknown = sorted(wanted - valid)
    if unknown:
        raise RuntimeError(f"unknown completion bar item(s): {', '.join(unknown)}")
    for item in data.get("completion_bar", []):
        if item.get("id") in wanted:
            item["done"] = done
            item["note"] = "manual completion update" if done else "reopened"
            changed.append(item.get("id"))
    return changed


def workpad(args):
    run_dir = resolve_run_dir(args.run_id)
    data = ensure_workpad(run_dir)
    append_workpad_items(data, "plan", args.plan)
    append_workpad_items(data, "reflections", args.reflection)
    append_workpad_items(data, "acceptance", args.acceptance)
    append_workpad_items(data, "validation", args.validation_item)
    append_workpad_items(data, "notes", args.note)
    append_workpad_items(data, "confusions", args.confusion)
    completed = mark_completion(data, args.complete, True)
    reopened = mark_completion(data, args.reopen, False)
    data = write_workpad(run_dir, data)
    changes = []
    for key in ("plan", "reflection", "acceptance", "validation_item", "note", "confusion"):
        if clean_items(getattr(args, key)):
            changes.append(key.replace("_item", ""))
    changes.extend([f"complete:{item}" for item in completed])
    changes.extend([f"reopen:{item}" for item in reopened])
    message = "workpad updated"
    if changes:
        message = "workpad updated: " + ", ".join(changes)
    append_event(run_dir, "workpad", message, args.phase or "workpad", {"workpad_path": rel(run_dir / "workpad.md")})
    if args.json:
        print(json.dumps({"workpad": data, "summary": workpad_summary(run_dir)}, ensure_ascii=False, indent=2))
    else:
        print(f"[OK] workpad recorded: {rel(run_dir / 'workpad.md')}")
    return 0


def check_run(run_dir):
    errors = []
    warnings = []
    task = load_yaml(run_dir / "task.yaml")
    state = read_json(run_dir / "state.json", {}) or {}
    validation_data = read_json(run_dir / "validation.json", {}) or {}
    validations = validation_data.get("items") or []
    required = clean_items(task.get("required_validation"))
    for command in required:
        matches = [item for item in validations if item.get("command") == command]
        if not matches:
            errors.append(f"Missing required validation: {command}")
        elif matches[-1].get("status") != "pass":
            errors.append(f"Required validation is not passing: {command}")
    if task.get("handoff_required", True) and not (run_dir / "handoff.md").exists():
        errors.append("handoff.md is missing")
    if not (run_dir / "workpad.md").exists():
        warnings.append("workpad.md is missing")
    else:
        pending = pending_completion(ensure_workpad(run_dir), 100)
        if pending:
            warnings.append("completion bar has pending item(s): " + ", ".join(pending))
    if not (run_dir / "result.md").exists():
        warnings.append("result.md is missing")
    for blocker in load_blockers(run_dir):
        if blocker.get("status") == "open" and state.get("status") not in {"blocked", "failed", "cancelled"}:
            warnings.append(f"Open blocker exists: {blocker.get('blocker_id')}")
    open_requests = pending_requests(run_dir)
    if open_requests:
        labels = ", ".join(item.get("request_id", "") for item in open_requests[:5])
        warnings.append(f"protocol request(s) still pending: {labels}")
    return errors, warnings


def check(args):
    run_dir = resolve_run_dir(args.run_id)
    errors, warnings = check_run(run_dir)
    for item in warnings:
        print(f"[WARN] {item}")
    for item in errors:
        print(f"[FAIL] {item}")
    if errors:
        return 1
    print("[OK] runtime run is complete enough for review")
    return 0


def complete(args):
    if args.status not in VALID_STATUSES:
        raise RuntimeError(f"status must be one of {sorted(VALID_STATUSES)}")
    run_dir = resolve_run_dir(args.run_id)
    state = update_state(run_dir, status=args.status, phase="complete", last_event=f"completed as {args.status}")
    task = load_yaml(run_dir / "task.yaml")
    ensure_workpad(run_dir)
    if not (run_dir / "result.md").exists():
        (run_dir / "result.md").write_text(result_skeleton(state, task), encoding="utf-8")
        print("[WARN] result.md was missing; generated a review skeleton")
    previous = read_json(run_dir / "handoff.json", {}) or {}
    completion_handoff = {
        "schema_version": SCHEMA_VERSION,
        "runtime_contract_version": RUNTIME_CONTRACT_VERSION,
        "kind": previous.get("kind") or "completion",
        "handoff_to": previous.get("handoff_to") or "any",
        "role": previous.get("role") or state.get("role") or "executor",
        "summary": previous.get("summary") or str(task.get("objective") or "No summary recorded."),
        "done": previous.get("done") or [],
        "next": previous.get("next") or ["Review acceptance criteria and result.md."],
        "context": previous.get("context") or [],
        "relevant_files": previous.get("relevant_files") or [],
        "tried": previous.get("tried") or [],
        "decisions": previous.get("decisions") or [],
        "constraints": previous.get("constraints") or [],
        "open_questions": previous.get("open_questions") or [],
        "risks": previous.get("risks") or [],
        "do_not_repeat": previous.get("do_not_repeat") or [],
        "evidence": previous.get("evidence") or [],
        "updated_at": now(),
    }
    write_json(run_dir / "handoff.json", completion_handoff)
    (run_dir / "handoff.md").write_text(
        render_handoff(run_dir, state, task, completion_handoff),
        encoding="utf-8",
    )
    append_event(run_dir, "complete", f"Run completed with status {args.status}", "complete")
    errors, warnings = check_run(run_dir)
    for item in warnings:
        print(f"[WARN] {item}")
    for item in errors:
        print(f"[WARN] {item}")
    print("[OK] run completion recorded")
    return 0


def resume(args):
    run_dir = resolve_run_dir(args.run_id)
    handoff = ""
    handoff_path = run_dir / "handoff.md"
    if handoff_path.exists():
        handoff = handoff_path.read_text(encoding="utf-8", errors="ignore")
    workpad_json = ensure_workpad(run_dir)
    workpad_path = run_dir / "workpad.md"
    workpad = workpad_path.read_text(encoding="utf-8", errors="ignore") if workpad_path.exists() else ""
    data = {
        "run_dir": rel(run_dir),
        "task": load_yaml(run_dir / "task.yaml"),
        "state": read_json(run_dir / "state.json", {}),
        "recent_events": read_events(run_dir, args.events),
        "blockers": load_blockers(run_dir),
        "requests": read_requests(run_dir),
        "validation": read_json(run_dir / "validation.json", {}),
        "handoff_json": read_json(run_dir / "handoff.json", {}),
        "handoff": handoff,
        "workpad_json": workpad_json,
        "workpad": workpad,
    }
    print(json.dumps(data, ensure_ascii=False, indent=2))
    return 0


def export_trace(args):
    run_dir = resolve_run_dir(args.run_id)
    state = read_json(run_dir / "state.json", {}) or {}
    task = load_yaml(run_dir / "task.yaml")
    task_slug = safe_slug(task.get("task_id") or state.get("task_id") or run_dir.name)
    path = trace_path(ROOT, f"{today()}-{task_slug}.md")
    path.parent.mkdir(parents=True, exist_ok=True)
    result = (run_dir / "result.md").read_text(encoding="utf-8", errors="ignore") if (run_dir / "result.md").exists() else ""
    handoff = (run_dir / "handoff.md").read_text(encoding="utf-8", errors="ignore") if (run_dir / "handoff.md").exists() else ""
    workpad = (run_dir / "workpad.md").read_text(encoding="utf-8", errors="ignore") if (run_dir / "workpad.md").exists() else ""
    lines = [
        "# Agent Runtime Trace",
        "",
        f"- Run: {state.get('run_id')}",
        f"- Task: {state.get('task_id')}",
        f"- Status: {state.get('status')}",
        f"- Runtime source: `{rel(run_dir)}`",
        "",
        "## Objective",
        "",
        str(task.get("objective") or ""),
        "",
        "## Changed Files",
        "",
        *[f"- {item}" for item in state.get("changed_files", [])],
        "",
        "## Recent Events",
        "",
    ]
    for item in read_events(run_dir, 50):
        lines.append(f"- {item.get('time')} [{item.get('type')}] {item.get('message')}")
    lines.extend(
        [
            "",
            "## Protocol Requests",
            "",
            *request_brief(run_dir, limit=20),
            "",
            "## Result",
            "",
            result or "- no result.md",
            "",
            "## Workpad",
            "",
            workpad or "- no workpad.md",
            "",
            "## Handoff",
            "",
            handoff or "- no handoff.md",
            "",
        ]
    )
    path.write_text("\n".join(lines), encoding="utf-8")
    print(f"[OK] exported trace: {rel(path)}")
    return 0


def archive(args):
    run_dir = resolve_run_dir(args.run_id)
    target = ARCHIVE_DIR / run_dir.name
    if target.exists():
        raise RuntimeError(f"Archive already exists: {rel(target)}")
    shutil.move(str(run_dir), str(target))
    pointer = current_pointer()
    if pointer.get("run_id") == run_dir.name and CURRENT_PATH.exists():
        CURRENT_PATH.unlink()
    print(f"[OK] archived {run_dir.name} to {rel(target)}")
    return 0


def add_common_run_arg(parser):
    _ = parser.add_argument("--run-id", default="", help="Run id. Defaults to current run.")


def add_handoff_args(parser, include_status=True):
    add_common_run_arg(parser)
    if include_status:
        _ = parser.add_argument("--status", default="paused")
    _ = parser.add_argument("--kind", default="continuation")
    _ = parser.add_argument("--handoff-to", default="any")
    _ = parser.add_argument("--role", default="")
    _ = parser.add_argument("--phase", default="")
    _ = parser.add_argument("--summary", default="")
    _ = parser.add_argument("--done", action="append")
    _ = parser.add_argument("--next", action="append")
    _ = parser.add_argument("--context", action="append")
    _ = parser.add_argument("--relevant-file", action="append")
    _ = parser.add_argument("--tried", action="append")
    _ = parser.add_argument("--decision", action="append")
    _ = parser.add_argument("--constraint", action="append")
    _ = parser.add_argument("--open-question", action="append")
    _ = parser.add_argument("--risk", action="append")
    _ = parser.add_argument("--do-not-repeat", action="append")
    _ = parser.add_argument("--evidence", action="append")


def parse_args():
    parser = argparse.ArgumentParser(description="Manage Harness local agent runtime records.")
    sub = parser.add_subparsers(dest="command", required=True)
    _ = sub.add_parser("init")
    p = sub.add_parser("discover")
    _ = p.add_argument("--json", action="store_true")
    p = sub.add_parser("status")
    _ = p.add_argument("--json", action="store_true")
    p = sub.add_parser("start")
    _ = p.add_argument("--objective", default="")
    _ = p.add_argument("--task", default="")
    _ = p.add_argument("--run-id", default="")
    _ = p.add_argument("--json", action="store_true")
    _ = p.add_argument("--assigned-by", default="local")
    _ = p.add_argument("--mode", choices=["local", "worker"], default="local")
    _ = p.add_argument("--task-type", default="implementation")
    _ = p.add_argument("--role", default="executor")
    _ = p.add_argument("--parent-run-id", default=None)
    _ = p.add_argument("--worker-id", default=None)
    _ = p.add_argument("--workspace", default=".")
    _ = p.add_argument("--branch", default=None)
    _ = p.add_argument("--attempt", type=int, default=1)
    _ = p.add_argument("--retry-of", default=None)
    p = sub.add_parser("current")
    _ = p.add_argument("--json", action="store_true")
    p = sub.add_parser("list")
    _ = p.add_argument("--json", action="store_true")
    _ = p.add_argument("--status", default="")
    p = sub.add_parser("show")
    add_common_run_arg(p)
    _ = p.add_argument("--json", action="store_true")
    _ = p.add_argument("--events", type=int, default=20)
    p = sub.add_parser("resume")
    add_common_run_arg(p)
    _ = p.add_argument("--json", action="store_true")
    _ = p.add_argument("--events", type=int, default=20)
    p = sub.add_parser("heartbeat")
    add_common_run_arg(p)
    _ = p.add_argument("--phase", default="running")
    p = sub.add_parser("event")
    add_common_run_arg(p)
    _ = p.add_argument("--type", default="note")
    _ = p.add_argument("--message", required=True)
    _ = p.add_argument("--phase", default="")
    p = sub.add_parser("validation")
    add_common_run_arg(p)
    _ = p.add_argument("--command", dest="validation_command", required=True)
    _ = p.add_argument("--status", required=True)
    _ = p.add_argument("--summary", default="")
    _ = p.add_argument("--evidence", action="append")
    p = sub.add_parser("eval")
    add_common_run_arg(p)
    _ = p.add_argument("--id", required=True)
    _ = p.add_argument("--type", default="runtime")
    _ = p.add_argument("--status", required=True)
    _ = p.add_argument("--summary", default="")
    _ = p.add_argument("--evidence", action="append")
    p = sub.add_parser("block")
    add_common_run_arg(p)
    _ = p.add_argument("--type", required=True)
    _ = p.add_argument("--message", required=True)
    _ = p.add_argument("--severity", default="medium")
    _ = p.add_argument("--needed-from", default="owner")
    _ = p.add_argument("--suggested-next-action", default="")
    _ = p.add_argument("--blocker-id", default="")
    _ = p.add_argument("--evidence", action="append")
    p = sub.add_parser("request")
    add_common_run_arg(p)
    _ = p.add_argument("--type", dest="request_type", required=True)
    _ = p.add_argument("--target", required=True)
    _ = p.add_argument("--summary", default="")
    _ = p.add_argument("--payload", default="")
    _ = p.add_argument("--payload-file", default="")
    _ = p.add_argument("--sender", default="")
    _ = p.add_argument("--request-id", default="")
    _ = p.add_argument("--evidence", action="append")
    _ = p.add_argument("--json", action="store_true")
    p = sub.add_parser("respond")
    add_common_run_arg(p)
    _ = p.add_argument("--request-id", required=True)
    _ = p.add_argument(
        "--status",
        required=True,
        choices=sorted(VALID_REQUEST_STATUSES - {"pending"}),
    )
    _ = p.add_argument("--feedback", default="")
    _ = p.add_argument("--responder", default="")
    _ = p.add_argument("--evidence", action="append")
    _ = p.add_argument("--force", action="store_true")
    _ = p.add_argument("--json", action="store_true")
    p = sub.add_parser("requests")
    add_common_run_arg(p)
    _ = p.add_argument("--status", default="")
    _ = p.add_argument("--type", dest="request_type", default="")
    _ = p.add_argument("--limit", type=int, default=0)
    _ = p.add_argument("--json", action="store_true")
    p = sub.add_parser("handoff")
    add_handoff_args(p)
    p = sub.add_parser("pause")
    add_handoff_args(p, include_status=False)
    p = sub.add_parser("next")
    _ = p.add_argument("--json", action="store_true")
    _ = p.add_argument("--limit", type=int, default=10)
    _ = p.add_argument("--status", default="")
    p = sub.add_parser("workpad")
    add_common_run_arg(p)
    _ = p.add_argument("--json", action="store_true")
    _ = p.add_argument("--phase", default="")
    _ = p.add_argument("--plan", action="append")
    _ = p.add_argument("--reflection", action="append")
    _ = p.add_argument("--acceptance", action="append")
    _ = p.add_argument("--validation", dest="validation_item", action="append")
    _ = p.add_argument("--note", action="append")
    _ = p.add_argument("--confusion", action="append")
    _ = p.add_argument("--complete", action="append", help="Mark a completion bar item id as done.")
    _ = p.add_argument("--reopen", action="append", help="Mark a completion bar item id as not done.")
    p = sub.add_parser("check")
    add_common_run_arg(p)
    p = sub.add_parser("complete")
    add_common_run_arg(p)
    _ = p.add_argument("--status", default="completed")
    p = sub.add_parser("export-trace")
    add_common_run_arg(p)
    p = sub.add_parser("archive")
    _ = p.add_argument("--run-id", required=True)
    return parser.parse_args()


def main():
    args = parse_args()
    commands = {
        "init": init_runtime,
        "discover": discover,
        "status": status,
        "start": start_run,
        "current": current_run,
        "list": list_runs,
        "show": show_run,
        "resume": resume,
        "heartbeat": heartbeat,
        "event": event,
        "validation": validation,
        "eval": eval_check,
        "block": block,
        "request": protocol_request,
        "respond": respond_request,
        "requests": list_protocol_requests,
        "handoff": handoff,
        "pause": pause,
        "next": next_runs,
        "workpad": workpad,
        "check": check,
        "complete": complete,
        "export-trace": export_trace,
        "archive": archive,
    }
    try:
        return commands[args.command](args)
    except RuntimeError as error:
        print(f"[FAIL] {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
