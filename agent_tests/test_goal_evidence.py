import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent"))
from m4core.conversation import missing_goal_evidence

class GoalEvidenceTests(unittest.TestCase):
    def test_html_calculator_cannot_be_replaced_by_python(self):
        self.assertTrue(missing_goal_evidence("اعمل حاسبة html وافتحها بالمتصفح", [], [{"display_name": "calculator.py"}]))
    def test_html_requires_open_and_dom_observation(self):
        goal = "اعمل حاسبة HTML وافتحها في المتصفح"
        artifacts = [{"display_name": "calculator.html", "mime": "text/html"}]
        self.assertTrue(missing_goal_evidence(goal, [], artifacts))
        steps = [{"name": "BrowserOpenArtifact", "ok": True}]
        self.assertTrue(missing_goal_evidence(goal, steps, artifacts))
        steps.append({"name": "BrowserReadDOM", "ok": True})
        self.assertEqual(missing_goal_evidence(goal, steps, artifacts), "")
    def test_search_url_dispatch_does_not_complete_video_request(self):
        goal = "افتح يوتيوب وابحث عن باب الحارة وافتح الفيديو"
        self.assertTrue(missing_goal_evidence(goal, [{"name":"PhoneOpenURL", "ok":True}], []))
        steps = [{"name":"BrowserReadDOM", "ok":True, "result":{"url":"https://www.youtube.com/watch?v=actual-observed-id"}}]
        self.assertEqual(missing_goal_evidence(goal, steps, []), "")
