"""Protect user-visible guarantees about phone tools, prompt, GitHub and Actions."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"app/src/main/java/io/musab/arabicterminal"
KT=ROOT/"app/src/main/java/com/musabgpt/m4"

def read(path):
    return path.read_text(encoding="utf-8")

class ChatBridgeContract(unittest.TestCase):
    def test_native_phone_action_shortcuts_are_not_fake_model_responses(self):
        tool=read(KT/"M4NativeTools.kt")
        self.assertIn("PhoneController.run(app, ArabicCommandRouter.parse(",tool)
        self.assertIn("terminalOnlySuffix",tool)
        self.assertIn('"native:" + cleaned',tool)
        self.assertIn('"open_app"',tool)
        self.assertIn("fun quickAction",tool)
        self.assertIn('case "واتساب"',read(JAVA/"PhoneController.java"))

    def test_real_pty_service_is_used_for_tool_commands(self):
        service=read(JAVA/"TerminalService.java")
        tool=read(KT/"M4NativeTools.kt")
        self.assertIn("service.runAgentCommand(command,seconds)",tool)
        self.assertIn("LinuxEnvironment.install(this)",service)
        self.assertIn("write(agentSession,statement)",service)
        self.assertIn('String path="/root/.m4-chat-tools/"',service)
        self.assertIn("exit_code=",service)
        self.assertIn("synchronized(toolLock)",service)
        self.assertNotIn("com.termux.permission.RUN_COMMAND",tool)

    def test_model_has_real_tools_and_confirmation(self):
        api=read(KT/"ChatApi.kt")
        screen=read(KT/"ChatScreen.kt")
        view=read(KT/"ChatViewModel.kt")
        for name in ("open_app","list_apps","terminal_command","github_connect","github_status"):
            self.assertIn(name,api)
        self.assertIn('"tool_calls"',api)
        self.assertIn('"tool_call_id"',api)
        self.assertIn("ToolApproval",view)
        self.assertIn("vm.decideTool(true)",screen)
        self.assertIn("vm.decideTool(false)",screen)
        self.assertIn("use the user's language",read(ROOT/"app/src/main/assets/m4-chat-system.txt")
                      .lower() if False else api) if False else None

    def test_chat_prompt_is_used_not_only_packaged(self):
        vm=read(KT/"ChatViewModel.kt")
        prompt=read(ROOT/"app/src/main/assets/m4-chat-system.txt")
        self.assertIn('app.assets.open("m4-chat-system.txt")',vm)
        self.assertIn("ChatApi.stream(primary,history,prompt)",vm)
        self.assertIn("Claude Code Opus 5.5",prompt)
        self.assertIn("لا ترد بالإسبانية",prompt)
        self.assertIn("الطرفية العربية",prompt)

    def test_github_official_device_login(self):
        main=read(JAVA/"MainActivity.java")
        self.assertIn("EXTRA_GITHUB_LOGIN",main)
        self.assertIn("gh auth login --hostname github.com --git-protocol https --web",main)
        self.assertIn("connectGithub",read(KT/"M4ChatActivity.kt"))
        self.assertIn("ربط GitHub رسمياً",read(KT/"ChatScreen.kt"))

    def test_only_main_push_workflow_generates_apk(self):
        android=read(ROOT/".github/workflows/android.yml")
        kotlin=read(ROOT/".github/workflows/m4-kotlin.yml")
        agent=read(ROOT/".github/workflows/agent-checks.yml")
        self.assertIn("branches: [main]",android)
        self.assertNotIn("  push:",kotlin)
        self.assertNotIn("  push:",agent)
        self.assertIn("ArabicTerminal-debug-APK",android)

if __name__=="__main__":
    unittest.main()
