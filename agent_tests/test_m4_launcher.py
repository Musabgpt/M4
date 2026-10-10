#!/usr/bin/env python3
"""Offline integration tests for the M4 Android terminal startup entrypoint."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "agent" / "m4-agent-start.sh"

class M4LauncherTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        base = Path(self.temp.name)
        self.project = base / "projects" / "M4"
        self.provider = base / "provider.env"
        self.provider.write_text(
            "export TERM_AGENT_API_BASE='https://example.com/v1'\n"
            "export TERM_AGENT_MODEL='test-model'\n",
            encoding="utf-8"
        )
        self.bin = base / "bin"
        self.bin.mkdir()
        agent = self.bin / "term-agent"
        agent.write_text("#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$M4_TEST_LOG\"\n", encoding="utf-8")
        agent.chmod(0o755)
        self.log = base / "calls.log"
        self.env = dict(os.environ, M4_AGENT_PROJECT=str(self.project),
                        M4_AGENT_PROVIDER_FILE=str(self.provider), M4_TEST_LOG=str(self.log),
                        PATH=str(self.bin) + os.pathsep + os.environ.get("PATH", ""))

    def invoke(self, goal="Fix M4"):
        return subprocess.run(["sh", str(SCRIPT), goal],
                              text=True, capture_output=True, env=self.env, timeout=15)

    def seed_checkout(self):
        (self.project / ".git").mkdir(parents=True)
        (self.project / "agent").mkdir()
        (self.project / "agent_tests").mkdir()
        (self.project / "agent" / "agent.py").write_text("# agent\n")

    def test_existing_checkout_initializes_real_checks_and_runs_goal(self):
        self.seed_checkout()
        result = self.invoke("Add tests & fix bug")
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = self.log.read_text()
        self.assertIn("check-add python3 -m unittest discover -s agent_tests -v", commands)
        self.assertIn("run Add tests & fix bug --steps 40 --allow-exec", commands)
        self.assertTrue((self.project / ".git").exists())

    def test_offline_seed_creates_editable_checkout(self):
        seed = Path(self.temp.name) / "seed"
        (seed / ".git").mkdir(parents=True)
        (seed / "agent").mkdir()
        (seed / "agent_tests").mkdir()
        (seed / "agent" / "agent.py").write_text("# bundled agent\n")
        self.env["M4_AGENT_SEED"] = str(seed)
        result = self.invoke("Fix a bug offline")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.project / ".git").is_dir())
        self.assertIn("offline M4 project", result.stdout)
        self.assertIn("run Fix a bug offline", self.log.read_text())

    def test_non_checkout_never_overwritten(self):
        self.project.mkdir(parents=True)
        important = self.project / "important.txt"
        important.write_text("preserve")
        result = self.invoke()
        self.assertEqual(result.returncode, 4)
        self.assertEqual(important.read_text(), "preserve")
        self.assertFalse(self.log.exists())

    def test_missing_provider_rejected(self):
        self.seed_checkout()
        self.provider.unlink()
        self.assertEqual(self.invoke().returncode, 3)
        self.assertFalse(self.log.exists())

    def test_checkout_must_have_m4_sources(self):
        (self.project / ".git").mkdir(parents=True)
        self.assertEqual(self.invoke().returncode, 6)
        self.assertFalse(self.log.exists())

    def test_empty_goal_does_not_run(self):
        self.seed_checkout()
        self.assertEqual(self.invoke("").returncode, 2)

if __name__ == "__main__":
    unittest.main()
