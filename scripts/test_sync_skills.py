"""Regression checks for skill registry projection."""

import importlib
import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, main

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

sync_skills = importlib.import_module("sync_skills")


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def write_skill(root, name, body=None):
    write(root / ".agent-harness" / "skills" / "src" / name / "SKILL.md", body or f"# {name}\n")


class SyncSkillsRegistryTests(TestCase):
    def setUp(self):
        self.previous_root = sync_skills.ROOT

    def tearDown(self):
        sync_skills.ROOT = self.previous_root

    def test_project_registry_extends_template_registry(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            sync_skills.ROOT = root
            write_skill(root, "base-skill")
            write_skill(root, "project-skill")
            write_skill(root, "project-all-agent-skill")
            write(
                root / ".agent-harness" / "skills" / "registry.yaml",
                """
skills:
  - name: base-skill
    source: src/base-skill
    targets:
      codex: true
      claude: true
      opencode: true
    owner: infra
    status: active
""".lstrip(),
            )
            write(
                root / ".agent-harness" / "skills" / "registry.project.yaml",
                """
skills:
  - name: project-skill
    source: src/project-skill
    targets:
      codex: true
      claude: false
      opencode: false
    owner: app-team
    status: active
  - name: project-all-agent-skill
    source: src/project-all-agent-skill
    targets:
      codex: true
      claude: true
      opencode: true
    owner: app-team
    status: active
""".lstrip(),
            )

            messages = []
            registries = sync_skills.load_registries()
            sync_skills.write_registry_projection(registries, "all", messages)
            sync_skills.check_registry_projection(registries, messages, "all")

            errors = [item for item in messages if item["level"] == "error"]
            self.assertEqual([], errors)
            self.assertTrue((root / ".agents" / "skills" / "base-skill" / "SKILL.md").exists())
            self.assertTrue((root / ".agents" / "skills" / "project-skill" / "SKILL.md").exists())
            self.assertFalse((root / ".claude" / "skills" / "project-skill" / "SKILL.md").exists())
            self.assertTrue((root / ".agents" / "skills" / "project-all-agent-skill" / "SKILL.md").exists())
            self.assertTrue((root / ".claude" / "skills" / "project-all-agent-skill" / "SKILL.md").exists())
            self.assertTrue((root / ".opencode" / "skills" / "project-all-agent-skill" / "SKILL.md").exists())

    def test_project_registry_cannot_redefine_template_skill(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            sync_skills.ROOT = root
            write_skill(root, "base-skill")
            write(
                root / ".agent-harness" / "skills" / "registry.yaml",
                """
skills:
  - name: base-skill
    source: src/base-skill
    targets:
      codex: true
    owner: infra
    status: active
""".lstrip(),
            )
            write(
                root / ".agent-harness" / "skills" / "registry.project.yaml",
                """
skills:
  - name: base-skill
    source: src/base-skill
    targets:
      codex: true
    owner: app-team
    status: active
""".lstrip(),
            )

            messages = []
            sync_skills.check_registry_projection(sync_skills.load_registries(), messages, "all")

            errors = [item["message"] for item in messages if item["level"] == "error"]
            self.assertTrue(any("Duplicate skill registry entry 'base-skill'" in item for item in errors))


if __name__ == "__main__":
    main()
