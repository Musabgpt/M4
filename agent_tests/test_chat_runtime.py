"""Normal-chat regression cases. Only the remote model is replaced by a fixture."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("m4_chat_agent", HERE / "agent/agent.py")
agent = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = agent
spec.loader.exec_module(agent)


def call(name, arguments):
    return {"role": "assistant", "tool_calls": [{"id": "fixture-call", "type": "function",
            "function": {"name": name, "arguments": json.dumps(arguments)}}]}


class NormalChatRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root, self.state = agent.workspace(self.temp.name)
        self.environment = patch.dict(os.environ, {"M4_OWNER_SESSION": "chat-test",
            "M4_TURN_ID": "turn-test", "NEWAL_TASK_STATE_HOME": str(self.state / "newal")})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def run_turn(self, messages, replies, mode="auto_project"):
        entry = getattr(agent, "chat_turn", None)
        self.assertTrue(callable(entry), "Normal Send has no conversation-aware tool runtime")
        request = {"owner_session_id": "chat-test", "turn_id": "turn-test", "messages": messages}
        with patch.object(agent, "ask", side_effect=replies), contextlib.redirect_stdout(io.StringIO()):
            return entry(self.root, self.state, request, approved=True, permission_mode=mode, max_steps=8)

    def test_normal_chat_uses_previous_code_and_publishes_real_html(self):
        html = '<!doctype html><html lang="ar" dir="rtl"><p>بمادا</p></html>'
        messages = [{"role": "assistant", "content": "```html\n" + html + "\n```"},
                    {"role": "user", "content": "احفظ هالملف باسم bamada.html"}]
        captured = []
        def provider(*args, **kwargs):
            captured.append(args[2])
            return call("ArtifactCreate", {"format": "html", "name": "bamada.html", "content": html}) if len(captured) == 1 else call("Finish", {"summary": "الملف مرفق"})
        result = self.run_turn(messages, provider)
        self.assertEqual(result["status"], "completed")
        self.assertTrue(any(html in item.get("content", "") for item in captured[0]))
        row = json.loads(next((self.state / "artifacts").glob("*/manifest.json")).read_text())
        self.assertTrue(row["published"])
        self.assertEqual(row["owner_session_id"], "chat-test")
        self.assertEqual(Path(row["verified_path"]).read_text(), html)
        self.assertEqual(row["sha256"], hashlib.sha256(html.encode()).hexdigest())
        self.assertEqual(result["artifacts"][0]["artifact_id"], row["artifact_id"])

    def test_python_request_runs_actual_program_and_attaches_it(self):
        program = "print([n for n in range(2, 12) if all(n % d for d in range(2, n))])\n"
        result = self.run_turn([{"role": "user", "content": "اعمل برنامج أعداد أولية احفظه py وشغله وارفقه"}],
            [call("Write", {"path": "primes.py", "content": program}),
             call("Bash", {"command": "python3 primes.py"}), call("Finish", {"summary": "نتيجة التنفيذ"})])
        self.assertEqual(result["status"], "completed")
        executed = [x for x in result["tools"] if x["name"] == "Bash"][0]
        self.assertEqual(executed["result"]["exit_code"], 0)
        self.assertEqual(executed["result"]["stdout"].strip(), "[2, 3, 5, 7, 11]")
        self.assertEqual(result["artifacts"][0]["display_name"], "primes.py")

    def test_plain_claim_cannot_complete_a_file_action(self):
        result = self.run_turn([{"role": "user", "content": "أنشئ ملف HTML وأرفقه"}],
            [{"role": "assistant", "content": "لقد حفظت الملف، استخدم PowerShell"}] * 8)
        self.assertEqual(result["status"], "blocked")
        self.assertEqual(result["artifacts"], [])
        self.assertNotIn("PowerShell", result["content"])

    def test_python_artifact_cannot_complete_html_browser_request(self):
        result = self.run_turn([{"role": "user", "content": "اعمل حاسبة HTML وافتحها بالمتصفح"}],
            [call("ArtifactCreate", {"format": "py", "name": "calculator.py", "content": "print(2+2)"}),
             call("Finish", {"summary": "جاهز"}), call("Finish", {"summary": "جاهز"})])
        self.assertEqual(result["status"], "blocked")
        self.assertIn("HTML", result["content"])

    def test_ordinary_question_can_answer_without_invoking_tools(self):
        result = self.run_turn([{"role": "user", "content": "ما معنى العدد الأولي؟"}],
            [{"role": "assistant", "content": "عدد أكبر من واحد لا يقبل القسمة إلا على نفسه وواحد."}])
        self.assertEqual(result["status"], "completed")
        self.assertIn("القسمة", result["content"])
        self.assertEqual(result["tools"], [])

    def test_plan_cannot_publish_files_despite_a_completion_claim(self):
        result = self.run_turn([{"role": "user", "content": "اكتب ملف txt"}],
            [call("ArtifactCreate", {"format": "txt", "name": "bad.txt", "content": "bad"}),
             call("Finish", {"summary": "تم إنشاء الملف"})], "plan_only")
        self.assertNotEqual(result["status"], "completed")
        self.assertEqual(result["artifacts"], [])
        self.assertFalse(list((self.state / "artifacts").glob("*/manifest.json")))

    def test_cancellation_is_a_durable_result_for_this_turn(self):
        result = self.run_turn([{"role": "user", "content": "اكتب برنامجًا"}], KeyboardInterrupt())
        self.assertEqual(result["status"], "canceled")
        self.assertEqual(result["turn_id"], "turn-test")
        rows = [json.loads(line) for line in (self.state / "events.jsonl").read_text().splitlines()]
        self.assertTrue(any(row["kind"] == "turn_finished" and row["data"]["turn_id"] == "turn-test" for row in rows))


if __name__ == "__main__":
    unittest.main()
