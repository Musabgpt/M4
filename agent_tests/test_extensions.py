"""Real extension activation, isolation and removal on the live registry."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE / "agent"))
spec = importlib.util.spec_from_file_location("extension_test_host", HERE / "agent/agent.py")
host = importlib.util.module_from_spec(spec); sys.modules[spec.name] = host; spec.loader.exec_module(host)
from m4core.runtime import ToolRuntime

class ExtensionsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(); self.addCleanup(self.directory.cleanup)
        self.root, self.state = host.workspace(self.directory.name)
        self.runtime = ToolRuntime(self.root, self.state, host.safe_path, host.backup, host.COMMANDS, host.command, approved=True)
        self.addCleanup(self.runtime.close)

    def test_factory_runs_actual_data_tool_and_disable_removes_it(self):
        created = json.loads(self.runtime.call("ToolFactory", {"name": "sum_numbers", "description": "Add input numbers",
            "properties": {"numbers": {"type": "array", "items": {"type": "number"}}}, "required": ["numbers"],
            "source": "def run(args):\n    return {'sum': sum(args['numbers'])}\n",
            "tests": [{"arguments": {"numbers": [2, 3, 7]}, "expected": {"sum": 12}}]}))
        self.assertEqual(created["status"], "activated")
        self.assertEqual(json.loads(self.runtime.call("user__sum_numbers", {"numbers": [11, -2]})), {"sum": 9})
        self.runtime.call("ExtensionDisable", {"name": "sum_numbers"})
        with self.assertRaises(ValueError): self.runtime.call("user__sum_numbers", {"numbers": [1]})

    def test_factory_failed_test_and_code_with_external_access_never_activate(self):
        base = {"name": "bad", "description": "bad", "properties": {}, "required": [], "tests": [{"arguments": {}, "expected": 2}]}
        for source in ("def run(args):\n    return 1\n", "def run(args):\n    return open('/root/.m4/secret').read()\n"):
            with self.assertRaises(ValueError): self.runtime.call("ToolFactory", dict(base, source=source))
        self.assertNotIn("user__bad", self.runtime.registry.entries)

    def test_skill_is_hot_loaded_only_after_real_tests_pass(self):
        created = json.loads(self.runtime.call("SkillCreate", {"name": "python_review", "content": "# Python review\nRead code, run authorized tests, report observed failures.",
            "script": "print('skill verification')\n", "test_command": "python3 script.py"}))
        self.assertEqual(created["status"], "activated")
        loaded = json.loads(self.runtime.call("SkillRead", {"name": "python_review"}))
        self.assertTrue(loaded["untrusted"])
        self.assertIn("Python review", loaded["content"])
        self.assertEqual(created["test"]["exit_code"], 0)

    def test_plan_cannot_install_an_extension(self):
        self.runtime.mode = "plan_only"
        with self.assertRaises(ValueError): self.runtime.call("SkillCreate", {"name": "bad", "content": "# bad", "script": "print(1)", "test_command": "python3 script.py"})
        self.assertFalse((self.root / ".m4/skills/bad").exists())

if __name__ == "__main__": unittest.main()
