# pyright: reportUnknownMemberType=false
"""Regression checks for harness-upgrade source provenance."""

import json
import subprocess
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, main

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent
UPGRADE = SCRIPT_DIR / "upgrade_harness.py"


def run(command, cwd, check=True):
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and result.returncode != 0:
        raise AssertionError(
            f"command failed: {command}\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return result


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def init_source_repo(path):
    write(path / "harness" / "VERSION", "9.9.9\n")
    write(
        path / "harness.example.yaml",
        """
project:
  name: "template"
  language: "python"
harness_framework:
  schema_version: 1
  template_version: "9.9.9"
  source: "{HARNESS_TEMPLATE_REMOTE_URL}"
  source_ref: "main"
  source_commit: null
  last_upgrade: null
platforms:
  python:
    path: "."
validation:
  lint_arch:
    enabled: true
  verify:
    enabled: true
""".lstrip(),
    )
    write(path / "scripts" / "validate.py", "print('new validate')\n")
    write(path / "README.md", "template readme must not be upgraded\n")
    run(["git", "init"], path)
    run(["git", "add", "."], path)
    run(
        [
            "git",
            "-c",
            "user.email=harness@example.invalid",
            "-c",
            "user.name=Harness Tests",
            "commit",
            "-m",
            "initial template",
        ],
        path,
    )
    return run(["git", "rev-parse", "HEAD"], path).stdout.strip()


def init_target(path, source):
    write(
        path / "harness.yaml",
        f"""
project:
  name: "target"
  language: "python"
harness_framework:
  schema_version: 1
  template_version: "1.0.0"
  source: "{source.as_posix()}"
  source_ref: "main"
  source_commit: null
  last_upgrade: null
platforms:
  python:
    path: "."
validation:
  lint_arch:
    enabled: true
  verify:
    enabled: true
""".lstrip(),
    )
    write(path / "README.md", "target readme is project-owned\n")


class HarnessUpgradeProvenanceTests(TestCase):
    def test_json_plan_includes_local_source_identity(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            target = root / "target"
            commit = init_source_repo(source)
            init_target(target, source)

            result = run(
                [
                    sys.executable,
                    str(UPGRADE),
                    "--source",
                    str(source),
                    "--target",
                    str(target),
                    "--mode",
                    "plan",
                    "--json",
                ],
                ROOT,
                check=False,
            )

            self.assertIn(result.returncode, {0, 2})
            payload = json.loads(result.stdout)
            self.assertEqual(payload["source_identity"]["source_kind"], "local")
            self.assertEqual(payload["source_identity"]["resolved_commit"], commit)
            self.assertFalse(payload["source_identity"]["source_dirty"])
            paths = {item["path"] for item in payload["actions"]}
            self.assertNotIn("README.md", paths)

    def test_source_commit_mismatch_refuses_apply_before_writing(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            target = root / "target"
            _ = init_source_repo(source)
            init_target(target, source)

            result = run(
                [
                    sys.executable,
                    str(UPGRADE),
                    "--source",
                    str(source),
                    "--source-commit",
                    "0" * 40,
                    "--target",
                    str(target),
                    "--mode",
                    "apply",
                    "--json",
                ],
                ROOT,
                check=False,
            )

            self.assertEqual(result.returncode, 1)
            self.assertIn("commit mismatch", result.stdout)
            self.assertFalse((target / "scripts" / "validate.py").exists())

    def test_apply_report_json_sidecar_contains_sha_evidence(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source = root / "source"
            target = root / "target"
            commit = init_source_repo(source)
            init_target(target, source)
            write(target / "scripts" / "validate.py", "print('old validate')\n")

            result = run(
                [
                    sys.executable,
                    str(UPGRADE),
                    "--source",
                    str(source),
                    "--source-commit",
                    commit,
                    "--target",
                    str(target),
                    "--mode",
                    "apply",
                    "--write-report",
                    "--json",
                ],
                ROOT,
                check=False,
            )

            self.assertIn(result.returncode, {0, 2})
            payload = json.loads(result.stdout)
            self.assertEqual(payload["source_identity"]["requested_commit"], commit)
            applied = {
                item["path"]: item
                for item in payload["applied"]
                if item["path"] == "scripts/validate.py"
            }
            self.assertIn("scripts/validate.py", applied)
            item = applied["scripts/validate.py"]
            self.assertIn("source_sha256", item)
            self.assertIn("previous_sha256", item)
            self.assertIn("backup_sha256", item)
            self.assertTrue((target / payload["report"]).exists())
            self.assertTrue((target / payload["report_json"]).exists())
            sidecar = json.loads((target / payload["report_json"]).read_text(encoding="utf-8"))
            self.assertEqual(sidecar["source_identity"]["resolved_commit"], commit)


if __name__ == "__main__":
    main()
