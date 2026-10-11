"""Independent contract test for the exact Python worker embedded in Kotlin.

No real Termux or GitHub credentials. A local fake NewAl server checks the
authenticated /api/sessions request/response flow and guards against stale
prior-turn replies.
"""
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import textwrap
import threading
import unittest


KOTLIN = Path(__file__).resolve().parents[1] / (
    "app/src/main/java/com/musabgpt/m4/terminal/NewAlTermuxBridge.kt"
)


def embedded_worker():
    source = KOTLIN.read_text(encoding="utf-8")
    start = "private val worker = " + '"""'
    end = '""".trimIndent()'
    if source.count(start) != 1 or source.count(end) != 1:
        raise AssertionError("Termux worker source not uniquely identifiable")
    value = textwrap.dedent(source.split(start, 1)[1].split(end, 1)[0])
    compile(value, "<M4-Termux-NewAl-worker>", "exec")
    return value


class NewAlFixture(http.server.BaseHTTPRequestHandler):
    state = None

    def log_message(self, *_):
        return

    def respond(self, data):
        raw = json.dumps(data).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def handle_api(self, payload=None):
        if self.headers.get("X-NewAl-Key") != "m4-test-session-key":
            self.send_error(401)
            return
        path = self.path
        st = self.state
        if path == "/api/state":
            return self.respond({"status": "ready"})
        if path == "/api/sessions" and payload is not None:
            st["mode"] = payload["mode"]
            return self.respond({"id": "contract-session"})
        if path == "/api/sessions/contract-session/send":
            st["send_calls"] += 1
            st["message"] = payload["text"]
            st["current"] = True
            return self.respond({"started": True, "session": "contract-session"})
        if path == "/api/sessions/contract-session":
            old = {"type": "turn_end", "turn": 5, "answer": "STALE-PRIOR-ANSWER"}
            fresh = {"type": "turn_end", "turn": 6, "answer": "NEW-VERIFIED-ANSWER"}
            return self.respond({
                "meta": {"turn": 6 if st["current"] else 5},
                "events": [old, fresh] if st["current"] else [old],
                "busy": False,
                "approvals": [],
            })
        self.send_error(404)

    def do_GET(self):
        self.handle_api()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.handle_api(json.loads(self.rfile.read(length)))


class TermuxNewAlContractTest(unittest.TestCase):
    def test_python_source_is_valid(self):
        self.assertIn("prior_turn", embedded_worker())

    def test_authenticated_new_turn_never_returns_stale_receipt(self):
        with socket.socket() as check:
            try:
                check.bind(("127.0.0.1", 8791))
            except OSError as error:
                self.skipTest("Port 8791 occupied: " + str(error))
        with tempfile.TemporaryDirectory() as tmp:
            home = Path(tmp)
            key = home / ".newal-cloud" / "key"
            key.parent.mkdir()
            key.write_text("m4-test-session-key")
            os.chmod(key, 0o600)
            state = {"send_calls": 0, "current": False, "message": ""}
            handler = type("ScopedNewAlFixture", (NewAlFixture,), {"state": state})
            server = http.server.ThreadingHTTPServer(("127.0.0.1", 8791), handler)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                environment = os.environ.copy()
                environment["HOME"] = str(home)
                run = subprocess.run(
                    [sys.executable, "-c", embedded_worker(), "test-owner",
                     __import__("base64").b64encode("افتح المشروع".encode()).decode(),
                     "plan_only"],
                    capture_output=True, text=True, timeout=12,
                    env=environment,
                )
                self.assertEqual(run.returncode, 0, run.stderr)
                receipt = json.loads(run.stdout.strip())
                self.assertEqual(receipt["content"], "NEW-VERIFIED-ANSWER")
                self.assertEqual(receipt["status"], "succeeded")
                self.assertEqual(state["send_calls"], 1)
                self.assertEqual(state["message"], "افتح المشروع")
                self.assertEqual(state["mode"], "read-only")
            finally:
                server.shutdown()
                server.server_close()
                thread.join(timeout=2)


if __name__ == "__main__":
    unittest.main()
