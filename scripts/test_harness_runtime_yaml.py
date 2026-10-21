# pyright: reportUnknownParameterType=false, reportUnknownArgumentType=false
"""Regression checks for the dependency-free Harness YAML fallback parser."""

import os
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, main
from unittest.mock import patch

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import agent_runtime
import harness_runtime


class HarnessRuntimeYamlFallbackTests(TestCase):
    def test_parse_yaml_empty_inline_values(self):
        data = harness_runtime.parse_yaml(
            """
schema_version: 1
required_validation: []
scope:
  allowed_paths: []
  metadata: {}
quoted_list: "[]"
command: 'python -m unittest discover -s scripts -p "test_*.py"'
non_empty:
  - python scripts/validate.py
"""
        )

        self.assertEqual(data["required_validation"], [])
        self.assertEqual(data["scope"]["allowed_paths"], [])
        self.assertEqual(data["scope"]["metadata"], {})
        self.assertEqual(data["quoted_list"], "[]")
        self.assertEqual(
            data["command"],
            'python -m unittest discover -s scripts -p "test_*.py"',
        )
        self.assertEqual(data["non_empty"], ["python scripts/validate.py"])

    def test_load_yaml_fallback_empty_required_validation(self):
        with TemporaryDirectory() as temp_dir:
            task_path = Path(temp_dir) / "task.yaml"
            task_path.write_text(
                """
schema_version: 1
task_type: plan
required_validation: []
acceptance: []
""",
                encoding="utf-8",
            )

            with patch("importlib.import_module", side_effect=ImportError):
                data = harness_runtime.load_yaml(task_path)

        self.assertEqual(data["required_validation"], [])
        self.assertEqual(data["acceptance"], [])

    def test_load_yaml_fallback_rejects_advanced_yaml(self):
        with TemporaryDirectory() as temp_dir:
            task_path = Path(temp_dir) / "task.yaml"
            task_path.write_text(
                """
base: &base
  required_validation: []
task:
  <<: *base
  note: |
    multiline values require PyYAML
""",
                encoding="utf-8",
            )

            with patch("importlib.import_module", side_effect=ImportError):
                with self.assertRaises(ValueError):
                    harness_runtime.load_yaml(task_path)

    def test_advanced_yaml_detection_ignores_quoted_content(self):
        features = harness_runtime.unsupported_fallback_yaml_features(
            """
note: "see *emphasis* and &refs inside quotes"
command: 'python a.py && python b.py'
pattern: "scripts/ *glob"
"""
        )

        self.assertEqual(features, [])

    def test_repo_role_survives_broken_local_config(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            (root / "harness.example.yaml").write_text(
                "project:\n  name: template\n", encoding="utf-8"
            )
            (root / "harness.local.yaml").write_text(
                "base: &base\n  repo_role: template\nharness_runtime:\n  <<: *base\n",
                encoding="utf-8",
            )

            with patch.dict(os.environ, {"HARNESS_REPO_ROLE": ""}):
                with patch("importlib.import_module", side_effect=ImportError):
                    role = harness_runtime.repo_role(root)

        self.assertEqual(role, "template")

    def test_agent_runtime_required_validation_scalar_guard(self):
        self.assertEqual(agent_runtime.clean_items("[]"), [])
        self.assertEqual(
            agent_runtime.clean_items("python scripts/validate.py"),
            ["python scripts/validate.py"],
        )


if __name__ == "__main__":
    main()
