"""Integration tests for M4 core entrypoint, provider fallback, and Git tools."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "agent"))
from m4core import runtime
from m4core.git_tools import git_diff, git_status, git_log, github_pr
from m4core.registry import ToolRegistry
from m4core.router import Route, Router

spec = importlib.util.spec_from_file_location("m4_runtime_test_agent", ROOT / "agent" / "agent.py")
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)


def reply(name, args):
    return ({"role": "assistant", "content": None,
             "tool_calls": [{"id": "same-id", "type": "function", "function":
                             {"name": name, "arguments": json.dumps(args)}}]}, "mock")


class TestLiveCore(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root, self.state = agent.workspace(self.temp.name)

    def test_new_loop_reads_edits_and_finishes_after_verification(self):
        (self.root / "x.py").write_text("old = 1\n")
        agent.save(self.state / "checks.json", ["python3 -V"])
        answers = [reply("Read", {"file_path": "x.py"}),
                   reply("Edit", {"file_path": "x.py", "old_string": "old", "new_string": "new"}),
                   reply("Finish", {"summary": "changed"})]
        with patch.object(runtime, "ask_core", side_effect=answers):
            self.assertEqual(runtime.run(agent, self.root, self.state,
                                         "update x", 3, approved=True), 0)
        self.assertEqual((self.root / "x.py").read_text(), "new = 1\n")
        self.assertEqual(agent.load(self.state / "state.json", {})["phase"], "complete")
        self.assertTrue((self.state / "backups.jsonl").exists())
        agent.rollback(self.root, self.state, "x.py")
        self.assertEqual((self.root / "x.py").read_text(), "old = 1\n")

    def test_default_run_cli_uses_new_core(self):
        with patch.object(agent, "run_m4core", return_value=13) as core, \
             patch.object(sys, "argv", ["agent.py", "--project", str(self.root),
                                       "run", "goal"]):
            self.assertEqual(agent.main(), 13)
            self.assertEqual(core.call_args.args[3], "goal")
            self.assertEqual(core.call_args.args[-1], "accept_edits")

    def test_auto_requires_exec_opt_in(self):
        with self.assertRaises(ValueError):
            runtime.run(agent, self.root, self.state, "goal", 1,
                        approved=False, mode="auto")

    def test_bash_dangerous_not_authorized_even_with_exec(self):
        answers = [reply("Bash", {"command": "rm -rf /"}),
                   reply("TodoWrite", {"todos": [{"content": "safe", "status": "completed"}]})]
        with patch.object(runtime, "ask_core", side_effect=answers):
            status = runtime.run(agent, self.root, self.state,
                                 "safe", 2, approved=True)
        self.assertEqual(status, 2)
        history = agent.history(self.state)
        try:
            events = history.execute("SELECT data FROM events WHERE kind='tool'").fetchall()
            self.assertIn("Not approved by user", events[0][0])
        finally:
            history.close()

    def test_prompted_fallback_when_native_returns_plain_text(self):
        router = Router([Route("r", "freellmpool", "Mock",
                               "https://example.org/v1", "m")], environ={})
        with patch.object(runtime, "_call_provider", side_effect=[
            {"content": "I will work on it"},
            {"content": '<m4_call>{"tool":"Read","args":{"file_path":"x.py"}}</m4_call>'}
        ]) as call:
            result, route = runtime.ask_core(router, [
                {"role": "system", "content": "help"},
                {"role": "user", "content": "read x.py"}])
        self.assertEqual(route, "Mock")
        self.assertEqual(result["tool_calls"][0]["function"]["name"], "Read")
        self.assertFalse(call.call_args_list[0].kwargs.get("prompted", False))
        self.assertTrue(call.call_args_list[1].kwargs["prompted"])

    def test_prompted_tool_history_has_no_tool_role(self):
        messages = [
            {"role": "system", "content": "agent"},
            {"role": "user", "content": "read"},
            {"role": "assistant", "content": None, "tool_calls": [
                {"id": "m4-1-0", "function":
                 {"name": "Read", "arguments": '{"file_path":"a"}'}}]},
            {"role": "tool", "tool_call_id": "m4-1-0", "content": "file"}]
        out = runtime._prompted_messages(messages)
        self.assertEqual([m["role"] for m in out],
                         ["system", "user", "assistant", "user"])
        self.assertIn("Available tools:", out[0]["content"])


class TestGitReadOnly(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        (self.root / "new.txt").write_text("test\n")

    def test_git_status_and_log(self):
        self.assertIn("new.txt", git_status(self.root))
        self.assertIn("Git error:", git_log(self.root))

    def test_diff_and_bad_public_pr(self):
        self.assertEqual(git_diff(self.root), "(no changes)")
        self.assertIn("Invalid repository", github_pr("bad repo!", 5))

    def test_registered_read_tools_need_no_permission(self):
        reg = ToolRegistry(self.root, mode="plan")
        self.addCleanup(reg.shell.close_all)
        self.assertIn("new.txt", reg.execute("GitStatus", {}))


if __name__ == "__main__":
    unittest.main()
