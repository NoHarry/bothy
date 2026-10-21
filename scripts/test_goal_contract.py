# pyright: reportUnknownMemberType=false
"""Regression checks for goal contract linting."""

import sys
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, main

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import lint_goal_contract


VALID_GOAL = """
# Goal Contract

- Project kind: greenfield

## 1. 唯一目标
Deliver a real product outcome.

## 2. 终态判定
- [ ] Core path works with validation evidence.

## 3. 结果计数
Update `BLACKBOARD.md` every round.

| Counter | Direction | Current | Target |
|---------|-----------|---------|--------|
| Core user paths complete | up | 0 | 1 |

## 4. 固定执行循环
Pick a counter, implement, validate, update the ledger.

## 5. 硬性禁止
- Do not use planner, auditor, evidence, or scaffold work as progress.

## 6. 受阻处理
Stop after 2 consecutive rounds without result counter movement.

## 7. 每轮自检
Which result counter changed?

## 8. 生命周期与变更规则
goal-contract.md is a requirement contract. Change it only after requirement completion, owner decision, or another recorded phase transition.
Archive the previous phase result in the changelog before replacing counters.
""".lstrip()


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class GoalContractLintTests(TestCase):
    def goal_path(self, root):
        return root / "doc" / "features" / "sample" / "outputs" / "goal-contract.md"

    def test_no_goal_contracts_skips_by_default(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)

            self.assertEqual(lint_goal_contract.validate(root), [])

    def test_valid_goal_contract_passes(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write(self.goal_path(root), VALID_GOAL)

            self.assertEqual(lint_goal_contract.validate(root), [])

    def test_terminal_section_requires_checklist(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write(self.goal_path(root), VALID_GOAL.replace("- [ ] Core path", "- Core path"))

            errors = lint_goal_contract.validate(root)

            self.assertTrue(any("终态判定" in message for message in errors))

    def test_explicit_file_is_validated(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write(root / "workstream-goal.md", VALID_GOAL)

            errors = lint_goal_contract.validate(root, files=["workstream-goal.md"])

            self.assertEqual(errors, [])

    def test_maintenance_project_kind_is_valid(self):
        with TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write(self.goal_path(root), VALID_GOAL.replace("Project kind: greenfield", "Project kind: maintenance"))

            self.assertEqual(lint_goal_contract.validate(root), [])


if __name__ == "__main__":
    main()
