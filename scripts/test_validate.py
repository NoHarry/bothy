# pyright: reportUnknownMemberType=false
"""Regression checks for validation pipeline routing."""

import sys
from pathlib import Path
from types import SimpleNamespace
from unittest import TestCase, main
from unittest.mock import patch

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import validate


class ValidatePipelineTests(TestCase):
    def test_test_step_uses_repo_command_when_configured(self):
        args = SimpleNamespace(step="test", dry_run=True)
        validation = {
            "test": {
                "enabled": True,
                "command": 'python -m unittest discover -s scripts -p "test_*.py"',
            }
        }
        platforms = {"python": {"path": ".", "test": "python -m pytest"}}

        with patch("validate.run_repo_step", return_value=0) as repo_step:
            with patch("validate.run_per_platform_step", return_value=0) as platform_step:
                result = validate.run_validation_steps(
                    args, validation, platforms, Path("harness.example.yaml")
                )

        self.assertEqual(result, 0)
        repo_step.assert_called_once()
        platform_step.assert_not_called()

    def test_test_step_falls_back_to_platform_commands_without_repo_command(self):
        args = SimpleNamespace(step="test", dry_run=True)
        validation = {"test": {"enabled": True}}
        platforms = {"python": {"path": ".", "test": "python -m pytest"}}

        with patch("validate.run_repo_step", return_value=0) as repo_step:
            with patch("validate.run_per_platform_step", return_value=0) as platform_step:
                result = validate.run_validation_steps(
                    args, validation, platforms, Path("harness.example.yaml")
                )

        self.assertEqual(result, 0)
        repo_step.assert_not_called()
        platform_step.assert_called_once()


if __name__ == "__main__":
    main()
