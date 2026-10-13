import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "agent"))
from m4core.memory import MemoryStore

class CloudMemoryTests(unittest.TestCase):
    def test_arabic_lessons_and_failures_survive_restart_without_secrets(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"M4_MEMORY_HOME": directory}):
            store = MemoryStore(directory)
            store.learn("العربية", "تحقق من الملف قبل نشره", "sha256 observed")
            store.learn("Python", "use UTF-8", "verified test")
            store.episode("failure", task="html", error="token=private-value", evidence="exit 1")
            store.close()
            reopened = MemoryStore(directory)
            try:
                self.assertEqual(reopened.recall("العربية", 1)[0]["topic"], "العربية")
                self.assertNotIn("private-value", reopened.recent_failures()[0]["error"])
                self.assertIn("[redacted]", reopened.recent_failures()[0]["error"])
            finally: reopened.close()

if __name__ == "__main__": unittest.main()
