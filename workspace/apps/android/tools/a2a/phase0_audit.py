#!/usr/bin/env python3
"""Fail-closed Phase 0 audit for the outbound Android A2A client."""

from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from pathlib import Path


ANDROID_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DIRECT_ONLY_APK_BYTES = 6_280_671
DEFAULT_APK_DELTA_BUDGET_BYTES = 1_572_864
FORBIDDEN_FACADE_ABI = (
    b"org/a2aproject",
    b"okhttp3/",
    b"kotlinx/serialization",
    b"com/google/gson",
    b"jakarta/",
)
FORBIDDEN_RUNTIME_TEXT = (
    "agent-a2a-client",
    "cc.noharry.agent.a2a",
    "org.a2aproject",
)
REQUIRED_OFFICIAL_DEPENDENCIES = (
    "org.a2aproject.sdk:a2a-java-sdk-client:1.1.0.Final",
    "org.a2aproject.sdk:a2a-java-sdk-http-client-android:1.1.0.Final",
    "org.a2aproject.sdk:a2a-java-sdk-spec-grpc:1.1.0.Final",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--direct-only-apk-bytes", type=int, default=DEFAULT_DIRECT_ONLY_APK_BYTES)
    parser.add_argument("--apk-delta-budget-bytes", type=int, default=DEFAULT_APK_DELTA_BUDGET_BYTES)
    parser.add_argument("--result-file", type=Path)
    args = parser.parse_args()
    checks: dict[str, object] = {}
    failures: list[str] = []
    check_release_artifacts(checks, failures, args.direct_only_apk_bytes, args.apk_delta_budget_bytes)
    check_isolation(checks, failures)
    check_official_metadata(checks, failures)
    check_r8(checks, failures)
    return emit_result(checks, failures, args.result_file)


def check_release_artifacts(
    checks: dict[str, object],
    failures: list[str],
    direct_only_apk_bytes: int,
    apk_delta_budget_bytes: int,
) -> None:
    artifacts = release_artifacts()
    missing = [name for name, path in artifacts.items() if not path.is_file()]
    record(checks, failures, "release_artifacts", not missing, {"missing": missing})
    if missing:
        return
    sizes = {name: path.stat().st_size for name, path in artifacts.items()}
    delta = sizes["sample_apk"] - direct_only_apk_bytes
    size_details = {
        **sizes,
        "direct_only_apk_bytes": direct_only_apk_bytes,
        "sample_apk_delta_bytes": delta,
        "sample_apk_delta_budget_bytes": apk_delta_budget_bytes,
    }
    record(checks, failures, "compressed_size", 0 <= delta <= apk_delta_budget_bytes, size_details)
    forbidden = facade_abi_hits(artifacts["facade_aar"])
    record(checks, failures, "facade_abi", not forbidden, {"forbidden_fragments": forbidden})


def check_isolation(checks: dict[str, object], failures: list[str]) -> None:
    runtime_hits = runtime_isolation_hits(ANDROID_ROOT / "agent-runtime")
    record(checks, failures, "agent_runtime_isolation", not runtime_hits, {"matches": runtime_hits})


def check_official_metadata(checks: dict[str, object], failures: list[str]) -> None:
    dependency_report = ANDROID_ROOT / "tools/a2a/official-release-dependencies.txt"
    dependency_text = read_text(dependency_report)
    dependency_missing = [item for item in REQUIRED_OFFICIAL_DEPENDENCIES if item not in dependency_text]
    dependency_ok = dependency_report.is_file() and not dependency_missing and "a2a-java-sdk-common:" not in dependency_text
    record(
        checks,
        failures,
        "official_dependency_graph",
        dependency_ok,
        {"missing": dependency_missing, "excluded_duplicate_common_jar": "a2a-java-sdk-common:" not in dependency_text},
    )

    notices = ANDROID_ROOT / "agent-a2a-client-official/THIRD_PARTY_NOTICES.md"
    notice_text = read_text(notices)
    license_ok = notices.is_file() and "1.1.0.Final" in notice_text and "Apache-2.0" in notice_text
    record(checks, failures, "official_license_notice", license_ok, {"path": str(notices.relative_to(ANDROID_ROOT))})


def check_r8(checks: dict[str, object], failures: list[str]) -> None:
    mapping = ANDROID_ROOT / "bothy-sample/build/outputs/mapping/release/mapping.txt"
    record(checks, failures, "sample_release_r8", mapping.is_file(), {"mapping": str(mapping.relative_to(ANDROID_ROOT))})


def emit_result(checks: dict[str, object], failures: list[str], result_file: Path | None) -> int:
    result = {"status": "passed" if not failures else "failed", "checks": checks, "failures": failures}
    serialized = json.dumps(result, sort_keys=True, separators=(",", ":"))
    if result_file:
        result_file.parent.mkdir(parents=True, exist_ok=True)
        result_file.write_text(serialized + "\n", encoding="utf-8")
    print(serialized)
    return 0 if not failures else 1


def release_artifacts() -> dict[str, Path]:
    return {
        "facade_aar": ANDROID_ROOT / "agent-a2a-client/build/outputs/aar/agent-a2a-client-release.aar",
        "direct_aar": ANDROID_ROOT / "agent-a2a-client-direct/build/outputs/aar/agent-a2a-client-direct-release.aar",
        "official_aar": ANDROID_ROOT / "agent-a2a-client-official/build/outputs/aar/agent-a2a-client-official-release.aar",
        "sample_apk": ANDROID_ROOT / "bothy-sample/build/outputs/apk/release/bothy-sample-release.apk",
    }


def facade_abi_hits(aar: Path) -> list[str]:
    with zipfile.ZipFile(aar) as archive:
        jar_bytes = archive.read("classes.jar")
    with zipfile.ZipFile(io.BytesIO(jar_bytes)) as classes_jar:
        classes = b"".join(
            classes_jar.read(name)
            for name in classes_jar.namelist()
            if name.endswith(".class")
        )
    hits = [fragment.decode("ascii") for fragment in FORBIDDEN_FACADE_ABI if fragment in classes]
    return hits


def runtime_isolation_hits(runtime: Path) -> list[str]:
    candidates = [runtime / "build.gradle.kts"]
    candidates.extend((runtime / "src/main").rglob("*.kt"))
    candidates.extend((runtime / "src/main").rglob("*.java"))
    hits: list[str] = []
    for path in candidates:
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for needle in FORBIDDEN_RUNTIME_TEXT:
            if needle in text:
                hits.append(f"{path.relative_to(ANDROID_ROOT)}:{needle}")
    return hits


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def record(
    checks: dict[str, object],
    failures: list[str],
    name: str,
    passed: bool,
    details: dict[str, object],
) -> None:
    checks[name] = {"status": "passed" if passed else "failed", **details}
    if not passed:
        failures.append(name)


if __name__ == "__main__":
    sys.exit(main())
