# pyright: reportUnknownMemberType=false
"""Regression checks for architecture dependency linting."""

import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, main

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import lint_deps


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class IsolationLintTests(TestCase):
    def test_isolation_import_prefix_only_allowed_in_bridge_path(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            old_root = lint_deps.ROOT
            lint_deps.ROOT = root
            try:
                write(
                    root / "workspace" / "apps" / "android" / "src" / "Feature.kt",
                    "import com.legacy.runtime.Tool\n",
                )
                write(
                    root
                    / "workspace"
                    / "apps"
                    / "android"
                    / "legacybridge"
                    / "Bridge.kt",
                    "import com.legacy.runtime.Tool\n",
                )
                config = {
                    "platforms": {"android": {"path": "workspace/apps/android"}},
                    "architecture": {
                        "layers": [],
                        "isolation": [
                            {
                                "import_prefix": "com.legacy.runtime",
                                "allowed_paths": ["workspace/apps/android/legacybridge/**"],
                            }
                        ],
                    },
                }

                rules = lint_deps.get_isolation_rules(config)
                violations = lint_deps.check_isolation_violations(rules, [], config)
            finally:
                lint_deps.ROOT = old_root

        self.assertEqual(len(violations), 1)
        self.assertEqual(violations[0]["path"], "workspace/apps/android/src/Feature.kt")


if __name__ == "__main__":
    main()
