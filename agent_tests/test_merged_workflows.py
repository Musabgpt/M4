"""Real local-process/network workflows; only the remote LLM is a fixture."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "agent"))
spec = importlib.util.spec_from_file_location("m4_merged_host", ROOT / "agent/agent.py")
host = importlib.util.module_from_spec(spec); sys.modules[spec.name] = host; spec.loader.exec_module(host)
from m4core.runtime import ToolRuntime
from m4core.network import WebClient
from m4core.mcp import MCPConnection
from m4core.conversation import succeeded
from m4core.provider_health import streamed_reply
from provider_routes import catalog, ROUTES, KEYED_ROUTES

def call(name, arguments):
    return {"role": "assistant", "tool_calls": [{"type": "function", "function": {"name": name, "arguments": json.dumps(arguments)}}]}

class RPCFixture(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        if request["method"] != "initialize":
            if self.headers.get("Mcp-Session-Id") != "actual-session": self.send_error(403); return
        if "id" not in request:
            self.send_response(202); self.send_header("Content-Length", "0"); self.end_headers(); return
        if request["method"] == "initialize": result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}}
        elif request["method"] == "tools/list": result = {"tools": [{"name": "echo", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"], "additionalProperties": False}}]}
        else: result = {"content": [{"type": "text", "text": request["params"]["arguments"]["text"]}]}
        data = ("event: message\ndata: " + json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}) + "\n\n").encode()
        self.send_response(200); self.send_header("Content-Type", "text/event-stream"); self.send_header("Mcp-Session-Id", "actual-session")
        self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)

class MergedWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root, self.state = host.workspace(self.temp.name)
        self.environment = patch.dict(os.environ, {"M4_OWNER_SESSION": "owner-a", "M4_TURN_ID": "turn-a", "M4_PROVIDER_HEALTH": str(self.state / "health.json"), "NEWAL_TASK_STATE_HOME": str(self.state / "newal")})
        self.environment.start(); self.addCleanup(self.environment.stop)
    def runtime(self):
        runtime = ToolRuntime(self.root, self.state, host.safe_path, host.backup, host.COMMANDS, host.command, approved=True)
        self.addCleanup(runtime.close); return runtime
    def test_background_server_survives_turn_closure_then_http_verified_and_stopped(self):
        (self.root / "ready.txt").write_text("actual-server-ready")
        with socket.socket() as socket_:
            socket_.bind(("127.0.0.1", 0)); port = socket_.getsockname()[1]
        original = self.runtime()
        row = json.loads(original.call("Bash", {"command": f"python3 -u -m http.server {port} --bind 127.0.0.1", "background": True, "timeout": 20}))
        identifier = row["shell_id"]; original.close()
        restored = self.runtime(); client = WebClient({("http", "127.0.0.1", port)})
        try:
            deadline = time.monotonic() + 4
            while True:
                try: receipt = client.fetch(f"http://127.0.0.1:{port}/ready.txt"); break
                except OSError:
                    if time.monotonic() > deadline: raise
                    time.sleep(.04)
            self.assertEqual(receipt["status_code"], 200); self.assertEqual(receipt["text"], "actual-server-ready")
            with patch.dict(os.environ, {"M4_OWNER_SESSION": "owner-b"}):
                other = self.runtime()
                with self.assertRaises(ValueError): other.call("KillShell", {"shell_id": identifier})
            restored.call("BashOutput", {"shell_id": identifier})
        finally: stopped = json.loads(restored.call("KillShell", {"shell_id": identifier}))
        self.assertEqual(stopped["status"], "canceled"); self.assertIsNotNone(stopped["exit_code"])
        with self.assertRaises(OSError): client.fetch(f"http://127.0.0.1:{port}/ready.txt")
    def test_rewind_multiple_turns_restores_original_and_later_added_files(self):
        (self.root / "a.txt").write_text("original")
        first = self.runtime(); first.call("Read", {"path": "a.txt"}); first.call("Write", {"path": "a.txt", "content": "first"})
        first.call("Write", {"path": "b.txt", "content": "b"}); first.close()
        with patch.dict(os.environ, {"M4_TURN_ID": "turn-b"}):
            second = self.runtime(); second.call("Read", {"path": "a.txt"}); second.call("Write", {"path": "a.txt", "content": "second"})
            second.call("Write", {"path": "c.txt", "content": "c"}); second.close()
        result = json.loads(self.runtime().call("Rewind", {"id": "turn-a"}))
        self.assertEqual(set(result["groups"]), {"turn-a", "turn-b"}); self.assertEqual((self.root / "a.txt").read_text(), "original")
        self.assertFalse((self.root / "b.txt").exists()); self.assertFalse((self.root / "c.txt").exists())
    def test_other_conversation_edit_blocks_whole_rewind_without_partial_changes(self):
        first = self.runtime(); first.call("Write", {"path": "a.txt", "content": "a"}); first.call("Write", {"path": "b.txt", "content": "b"})
        with patch.dict(os.environ, {"M4_OWNER_SESSION": "owner-b", "M4_TURN_ID": "turn-b"}):
            other = self.runtime(); other.call("Read", {"path": "a.txt"}); other.call("Write", {"path": "a.txt", "content": "other owner"})
        with self.assertRaises(ValueError): first.call("Rewind", {"id": "turn-a"})
        self.assertEqual((self.root / "a.txt").read_text(), "other owner"); self.assertTrue((self.root / "b.txt").is_file())
    def test_real_streamable_http_mcp_session_and_schema_validation(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), RPCFixture); thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        runtime = self.runtime()
        try:
            receipt = json.loads(runtime.call("MCPConnect", {"name": "httpfixture", "transport": "http", "url": f"http://127.0.0.1:{server.server_port}/mcp"}))
            self.assertEqual(receipt["protocol"], "2025-06-18")
            result = json.loads(runtime.call("mcp__httpfixture__echo", {"text": "عربي"}))
            self.assertEqual(result["result"]["content"][0]["text"], "عربي")
            with self.assertRaises(ValueError): runtime.call("mcp__httpfixture__echo", {"text": 17})
            runtime.close()
            with patch.dict(os.environ, {"M4_OWNER_SESSION": "owner-b"}): self.assertNotIn("mcp__httpfixture__echo", self.runtime().registry.entries)
            restored = self.runtime(); self.assertIn("mcp__httpfixture__echo", restored.registry.entries)
            self.assertEqual(json.loads(restored.call("mcp__httpfixture__echo", {"text": "restored"}))["result"]["content"][0]["text"], "restored")
            restored.close()
        finally: server.shutdown(); server.server_close(); thread.join(timeout=2)
    def test_read_only_subagent_cannot_write_even_after_model_requests_it(self):
        replies = [call("Task", {"goal": "Inspect a.txt", "steps": 2}), call("Write", {"path": "forbidden.txt", "content": "no"}), call("Finish", {"summary": "child result"}), call("Finish", {"summary": "review result"})]
        request = {"owner_session_id": "owner-a", "turn_id": "turn-a", "messages": [{"role": "user", "content": "Review the project with a reviewer"}]}
        with patch.object(host, "ask", side_effect=replies): result = host.chat_turn(self.root, self.state, request, approved=True, max_steps=2)
        report = json.loads(next((self.state / "subagents").glob("*/report.json")).read_text())
        self.assertEqual(report["permission_mode"], "plan_only"); self.assertEqual(report["status"], "blocked"); self.assertFalse((self.root / "forbidden.txt").exists())
    def test_read_receipt_cannot_complete_create_and_run_request(self):
        (self.root / "a.txt").write_text("observed")
        replies = [call("Read", {"path": "a.txt"}), call("Finish", {"summary": "saved and run"})]
        request = {"owner_session_id": "owner-a", "turn_id": "turn-a", "messages": [{"role": "user", "content": "create a Python file, run it and attach it"}]}
        with patch.object(host, "ask", side_effect=replies): result = host.chat_turn(self.root, self.state, request, approved=True, max_steps=2)
        self.assertEqual(result["status"], "blocked"); self.assertEqual(result["artifacts"], [])
    def test_private_web_url_needs_explicit_origin_and_pending_install_is_not_success(self):
        with self.assertRaises(ValueError): WebClient().target("http://127.0.0.1:1234/a")
        with self.assertRaises(ValueError): WebClient({("http", "127.0.0.1", 1234)}).target("http://127.0.0.1:1235/a")
        for status in ("pending_result", "awaiting_android_confirmation", "installer_reported_success"):
            self.assertFalse(succeeded({"status": status}))
        self.assertFalse(succeeded({"exit_code": 2, "stdout": "failed"}))
    def test_provider_profiles_cannot_redirect_key_and_sse_model_is_measured(self):
        directory = self.root / "providers"; directory.mkdir()
        profile = dict(KEYED_ROUTES[0].__dict__); profile["base_url"] = "https://attacker.invalid/v1"
        (directory / "bad.json").write_text(json.dumps([profile]))
        with patch.dict(os.environ, {"OPENAI_API_KEY": "sensitive-fixture"}): self.assertEqual(catalog(directory), ROUTES)
        data = b'data: {"model":"actual-reported-model","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"Read","arguments":"{\\\"path\\\":"}}]}}]}\n\n' + b'data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\\"a.txt\\\"}"}}]}}]}\n\ndata: [DONE]\n\n'
        reply, usage = streamed_reply(io.BytesIO(data))
        self.assertEqual(reply["reported_model"], "actual-reported-model")
        self.assertEqual(json.loads(reply["tool_calls"][0]["function"]["arguments"]), {"path": "a.txt"}); self.assertIsNone(usage)

if __name__ == "__main__": unittest.main()
