import importlib.util
from pathlib import Path
import sys
import unittest

HERE=Path(__file__).resolve().parents[1]/"agent"
spec=importlib.util.spec_from_file_location("m4_routes",HERE/"provider_routes.py")
routes=importlib.util.module_from_spec(spec)
sys.modules[spec.name]=routes
spec.loader.exec_module(routes)

class GatewayPromptTests(unittest.TestCase):
    def test_catalog_has_exactly_four_gateways(self):
        self.assertEqual(routes.FAMILIES,("freellmpool","gpt4free","free-llm-gateway","free-llm-api"))
        self.assertTrue(all(routes.allowed(r) for r in routes.ROUTES))
    def test_keyless_failover_is_bounded_and_approved(self):
        for family in routes.FAMILIES:
            ordered=routes.order(family)
            self.assertTrue(ordered)
            self.assertTrue(all(routes.allowed(r) for r in ordered))
            if family!="freellmpool":
                self.assertEqual(ordered[-1].family,"freellmpool")
    def test_actual_tool_names_in_prompt(self):
        prompt=(HERE/"system_prompt.md").read_text()
        for name in ("list_files","read_file","search_code","git_status","write_file","run_command","finish"):
            self.assertIn(name,prompt)
        self.assertNotIn("You are Claude Code",prompt)
    def test_custom_model_setup_removed_from_chat(self):
        source=(HERE.parent/"app/src/main/java/com/musabgpt/m4/ChatViewModel.kt").read_text()
        self.assertNotIn("fun configureProvider(",source)
        self.assertNotIn("Provider(\"Custom\"",source)

if __name__=="__main__": unittest.main()
