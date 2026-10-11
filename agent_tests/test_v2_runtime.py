"""Behavioral regression tests for the real V2 runtime, without remote providers."""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import sys
import tempfile
import time
import unittest
import zipfile
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1] / "agent"
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location("m4_v2_test_agent", HERE / "agent.py")
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)
from m4core.runtime import ToolRuntime
from m4core.registry import prompted_calls


class V2RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root, self.state = agent.workspace(self.temp.name)
        self.runtime = ToolRuntime(self.root, self.state, agent.safe_path, agent.backup,
                                   agent.COMMANDS, agent.command, approved=True)
        self.addCleanup(self.runtime.close)
        self.patch = patch.dict(os.environ, {"NEWAL_TASK_STATE_HOME": str(self.state / "newal")})
        self.patch.start(); self.addCleanup(self.patch.stop)

    def call(self, name, args):
        return self.runtime.call(name, args)

    def read(self, name):
        return self.call("Read", {"path": name})

    def test_schema_unknown_arguments_and_wrong_types_do_not_execute(self):
        with self.assertRaises(ValueError):
            self.call("Write", {"path": "unexpected.txt", "content": "bad", "approved": True})
        with self.assertRaises(ValueError):
            self.call("Read", {"path": "missing", "offset": True})
        self.assertFalse((self.root / "unexpected.txt").exists())

    def test_existing_write_requires_unchanged_read(self):
        path = self.root / "a.txt"; path.write_text("old")
        with self.assertRaises(ValueError):
            self.call("Write", {"path": "a.txt", "content": "new"})
        self.read("a.txt")
        path.write_text("external change")
        with self.assertRaises(ValueError):
            self.call("Write", {"path": "a.txt", "content": "new"})
        self.assertEqual(path.read_text(), "external change")

    def test_read_offset_limit_and_binary_rejection(self):
        (self.root / "a.txt").write_text("one\ntwo\nthree\n")
        data = json.loads(self.call("Read", {"path": "a.txt", "offset": 2, "limit": 1}))
        self.assertEqual(data["content"], "2: two")
        (self.root / "binary").write_bytes(b"hello\0world")
        with self.assertRaises(ValueError): self.read("binary")

    def test_multiedit_is_atomic_and_backup_restores_original(self):
        path = self.root / "a.txt"; path.write_text("one two one")
        self.read("a.txt")
        with self.assertRaises(ValueError):
            self.call("Edit", {"path": "a.txt", "old": "one", "new": "three"})
        with self.assertRaises(ValueError):
            self.call("MultiEdit", {"path": "a.txt", "edits": [{"old": "two", "new": "three"}, {"old": "missing", "new": "four"}]})
        self.assertEqual(path.read_text(), "one two one")
        self.call("Edit", {"path": "a.txt", "old": "one", "new": "three", "replace_all": True})
        self.assertEqual(path.read_text(), "three two three")
        agent.rollback(self.root, self.state, "a.txt")
        self.assertEqual(path.read_text(), "one two one")

    def test_plan_mode_blocks_all_mutations_and_execution(self):
        self.runtime.mode = "plan_only"
        for name, args in [("Write", {"path": "a", "content": "bad"}),
                           ("Bash", {"command": "python3 -V"}),
                           ("ArtifactCreate", {"format": "txt", "name": "a", "content": "bad"}),
                           ("TodoWrite", {"todos": []})]:
            with self.assertRaises(ValueError): self.call(name, args)
        self.assertFalse((self.root / "a").exists())

    def test_protected_paths_and_secrets_are_unavailable(self):
        for name in [".env", ".env.production", "secret.pem", "id_ed25519"]:
            (self.root / name).write_text("private")
            with self.assertRaises(ValueError): self.read(name)
        with self.assertRaises(ValueError):
            self.call("Write", {"path": "android/signing-cert-sha256.txt", "content": "bad"})
        with self.assertRaises(ValueError):
            self.call("Write", {"path": ".github/workflows/disabled.yml", "content": "bad"})

    def test_json_prompted_calls_require_valid_registry_envelope(self):
        calls = prompted_calls('{"tool_calls":[{"name":"Read","arguments":{"path":"a.txt"}}]}', self.runtime.registry)
        self.assertEqual(calls[0]["function"]["name"], "Read")
        for text in ['{"tool_calls":[{"name":"Phone","arguments":{}}]}',
                     'Run this: {"tool_calls":[]}', '```json\n{"tool_calls":[]}\n```',
                     '{"tool_calls":[{"name":"Bash","arguments":{"command":"ls","approved":true}}]}']:
            with self.assertRaises(ValueError): prompted_calls(text, self.runtime.registry)

    def test_text_formats_validate_actual_bytes_and_persistent_publish(self):
        contents = {"md": "# العربية\n", "txt": "نص\n", "html": "<!doctype html><p>العربية</p>",
                    "py": "print(1 + 2)\n", "sh": "#!/bin/sh\nprintf 'hello\\n'\n", "json": '{"a":1}'}
        ids = []
        with patch.dict(os.environ, {"M4_OWNER_SESSION": "conversation-1"}):
            for extension, content in contents.items():
                row = json.loads(self.call("ArtifactCreate", {"format": extension, "name": "sample", "content": content}))
                self.assertEqual(Path(row["verified_path"]).read_text(), content)
                self.assertEqual(row["owner_session_id"], "conversation-1")
                self.assertTrue(row["published"])
                self.assertEqual(json.loads(self.call("ArtifactInspect", {"id": row["artifact_id"]}))["sha256"], row["sha256"])
                ids.append(row["artifact_id"])
            archive = json.loads(self.call("ArtifactBundle", {"ids": ids, "name": "bundle"}))
        with zipfile.ZipFile(archive["verified_path"]) as zip_file:
            self.assertIsNone(zip_file.testzip()); self.assertEqual(len(zip_file.namelist()), 6)
        restored = ToolRuntime(self.root, self.state, agent.safe_path, agent.backup, agent.COMMANDS, agent.command)
        self.addCleanup(restored.close)
        self.assertTrue(json.loads(restored.call("ArtifactInspect", {"id": archive["artifact_id"]}))["published"])

    def test_invalid_formats_never_publish_an_attachment(self):
        for extension, content in [("json", "bad"), ("py", "def broken(:"), ("sh", "if then"), ("pdf", "pretend PDF"), ("apk", "pretend APK")]:
            with self.assertRaises((ValueError, SyntaxError)):
                self.call("ArtifactCreate", {"format": extension, "name": "invalid", "content": content})
        self.assertEqual(list((self.state / "artifacts").glob("*/manifest.json")), [])

    def test_artifact_hash_tampering_and_archive_traversal_rejected(self):
        row = json.loads(self.call("ArtifactCreate", {"format": "txt", "name": "a", "content": "good"}))
        Path(row["verified_path"]).write_text("evil")
        with self.assertRaises(ValueError): self.call("ArtifactInspect", {"id": row["artifact_id"]})
        with self.assertRaises(ValueError): self.call("ArtifactBundle", {"name": "bad", "paths": ["../outside"]})
        self.assertFalse((self.root.parent / "outside").exists())

    def test_background_process_streams_and_can_be_canceled(self):
        script = "import time; print('ready', flush=True); time.sleep(30)"
        started = json.loads(self.call("Bash", {"command": "python3 -u -c " + shlex.quote(script), "background": True, "timeout": 60}))
        output = started["stdout"]
        deadline = time.monotonic() + 3
        while "ready" not in output and time.monotonic() < deadline:
            time.sleep(0.02)
            output += json.loads(self.call("BashOutput", {"shell_id": started["shell_id"]}))["stdout"]
        self.assertIn("ready", output)
        killed = json.loads(self.call("KillShell", {"shell_id": started["shell_id"]}))
        self.assertEqual(killed["status"], "canceled")
        self.assertIsNotNone(killed["exit_code"])
        with self.assertRaises(ValueError): self.call("BashOutput", {"shell_id": "expired"})

    def test_process_timeout_output_cap_and_secret_scrubbing(self):
        script = "import os; print(os.environ.get('PROVIDER_TOKEN', 'removed')); print('x'*70000)"
        with patch.dict(os.environ, {"PROVIDER_TOKEN": "must-not-leak"}):
            row = json.loads(self.call("Bash", {"command": "python3 -c " + shlex.quote(script)}))
        self.assertEqual(row["exit_code"], 0); self.assertTrue(row["truncated"])
        self.assertIn("removed", row["stdout"]); self.assertNotIn("must-not-leak", row["stdout"])
        timed = json.loads(self.call("Bash", {"command": "python3 -c 'import time; time.sleep(5)'", "timeout": 1}))
        self.assertEqual(timed["status"], "timed_out"); self.assertIsNotNone(timed["exit_code"])

    def test_todo_persistence_and_conflicting_active_items(self):
        todos = [{"id": "a", "content": "work", "status": "in_progress"}]
        self.call("TodoWrite", {"todos": todos})
        self.assertEqual(json.loads((self.state / "todos.json").read_text()), todos)
        with self.assertRaises(ValueError): self.call("TodoWrite", {"todos": todos + [{"id": "b", "content": "other", "status": "in_progress"}]})

    def test_unavailable_bridge_does_not_register_device_actions(self):
        self.assertNotIn("Phone", self.runtime.registry.entries)
        self.assertNotIn("ScheduleCreate", self.runtime.registry.entries)
        self.assertNotIn("BrowserObserve", self.runtime.registry.entries)

    def test_changing_call_ids_do_not_hide_a_no_progress_loop(self):
        (self.root / "README.md").write_text("same")
        def reply(*args):
            return {"tool_calls": [{"id": "different-" + str(time.time_ns()), "function": {"name": "Read", "arguments": '{"path":"README.md"}'}}]}
        with patch.object(agent, "ask", side_effect=reply):
            self.assertEqual(agent.loop(self.root, self.state, "Inspect repeatedly", 10, False), 3)
        result = agent.load(self.state / "state.json", {})
        self.assertEqual(result["phase"], "stuck"); self.assertEqual(result["step"], 6)

    def test_plan_finish_never_runs_independent_commands(self):
        reply = {"tool_calls": [{"function": {"name": "Finish", "arguments": '{"summary":"plan"}'}}]}
        with patch.object(agent, "ask", return_value=reply), patch.object(agent, "verify") as verify:
            self.assertEqual(agent.loop(self.root, self.state, "Plan a change", 1, False, "plan_only"), 0)
        verify.assert_not_called()
        self.assertEqual(agent.load(self.state / "state.json", {})["phase"], "planned")

    def test_concurrent_agent_is_blocked_before_state_changes(self):
        import fcntl
        with (self.state / "active.lock").open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertEqual(agent.loop(self.root, self.state, "must not start", 1, True), 4)
            self.assertFalse((self.state / "state.json").exists())

    def test_interrupt_persists_canceled_phase(self):
        with patch.object(agent, "ask", side_effect=KeyboardInterrupt):
            self.assertEqual(agent.loop(self.root, self.state, "cancel this", 1, True), 130)
        self.assertEqual(agent.load(self.state / "state.json", {})["phase"], "canceled")

    def test_reserved_manifest_name_is_rejected(self):
        with self.assertRaises(ValueError):
            self.call("ArtifactCreate", {"format": "json", "name": "manifest.json", "content": '{}'})

    def test_grep_include_glob_cannot_override_secret_exclusions(self):
        (self.root / ".env.production").write_text("CONFIDENTIAL=hidden")
        self.assertNotIn("CONFIDENTIAL", self.call("Grep", {"pattern": "CONFIDENTIAL", "glob": ".env*"}))

    def test_compaction_preserves_complete_tool_call_pairs_and_goal(self):
        (self.root / "a.txt").write_text("\n".join(str(i) for i in range(20)))
        dialogs = []
        def reply(url, model, dialog):
            snapshot = json.loads(json.dumps(dialog)); dialogs.append(snapshot)
            pending = set()
            for message in snapshot:
                if message.get("role") == "assistant" and message.get("tool_calls"):
                    self.assertFalse(pending)
                    pending = {item["id"] for item in message["tool_calls"]}
                if message.get("role") == "tool":
                    self.assertIn(message["tool_call_id"], pending); pending.remove(message["tool_call_id"])
            self.assertFalse(pending)
            return {"tool_calls": [{"function": {"name": "Read", "arguments": json.dumps({"path": "a.txt", "offset": len(dialogs), "limit": 1})}}]}
        with patch.object(agent, "ask", side_effect=reply):
            self.assertEqual(agent.loop(self.root, self.state, "Keep the original goal", 12, False), 2)
        self.assertTrue(any("Compacted observed task state" in str(message) for message in dialogs[-1]))
        self.assertIn("Keep the original goal", str(dialogs[-1]))

    def test_invalid_finish_arguments_cannot_claim_verification(self):
        reply = {"tool_calls": [{"function": {"name": "Finish", "arguments": '{"summary":"pretend","success":true}'}}]}
        with patch.object(agent, "ask", return_value=reply), patch.object(agent, "verify") as verify:
            self.assertEqual(agent.loop(self.root, self.state, "Do real work", 1, True), 2)
        verify.assert_not_called()
        self.assertNotEqual(agent.load(self.state / "state.json", {})["phase"], "complete")


if __name__ == "__main__": unittest.main()
