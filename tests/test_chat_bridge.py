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
        self.assertIn("service.runAgentCommand(command, seconds)",tool)
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
        self.assertIn("InChatTerminal",read(KT/"ChatViewModel.kt"))
        self.assertIn("gh auth login --hostname github.com --git-protocol https --web",read(KT/"ChatScreen.kt"))
        self.assertIn("ربط GitHub رسمياً",read(KT/"ChatScreen.kt"))


    def test_alpine_terminal_is_the_chat_itself_not_a_sheet_or_second_input(self):
        core=read(KT/"InChatTerminal.kt")
        service=read(JAVA/"TerminalService.java")
        screen=read(KT/"ChatScreen.kt")
        vm=read(KT/"ChatViewModel.kt")
        activity=read(KT/"M4ChatActivity.kt")
        self.assertIn("openChatSession()",core)
        self.assertIn("backend.write(shell,",core)
        self.assertIn("backend.renderChatSession(shell)",core)
        self.assertIn("chatSession=create(false,true",service)
        self.assertIn("private Session chatSession;",service)
        self.assertNotIn("MainActivity::class.java",activity)
        self.assertNotIn("EmbeddedTerminalSheet(",screen)
        self.assertNotIn("ModalBottomSheet(",screen)
        self.assertIn("vm.terminalMode.collectAsState()",screen)
        self.assertIn("fun leaveTerminal()",vm)
        self.assertIn("sendTerminalLine(clean)",vm)
        self.assertIn("inChatTerminal.writeLine(line)",vm)
        self.assertIn("queuedTerminalLines",vm)
        self.assertIn("m4-agent-start ",screen)
        self.assertIn("provider.env",core)
        self.assertIn('role="terminal"',vm)

    def test_model_terminal_streams_cancels_and_remembers_cwd(self):
        service=read(JAVA/"TerminalService.java")
        bridge=read(KT/"M4NativeTools.kt")
        vm=read(KT/"ChatViewModel.kt")
        screen=read(KT/"ChatScreen.kt")
        api=read(KT/"ChatApi.kt")
        self.assertIn("cancelAgentCommand()",service)
        self.assertIn("cancelTerminalCommand()",vm)
        self.assertIn("readAgentOutput",service)
        self.assertIn("onOutput.accept(text)",service)
        self.assertIn("current.cwd",service)
        self.assertIn("pwd -P",service)
        self.assertIn("MAX_TOOL_ROUNDS=12",api)
        self.assertIn("identicalObservations>=2",api)
        self.assertIn("val terminalOutput by vm.terminalOutput.collectAsState()",screen)
        self.assertIn("service.runAgentCommand(command, seconds)",bridge)

    def test_whatsapp_compound_task_is_not_an_app_name(self):
        parser=read(KT/"WhatsAppPlanner.kt")
        bridge=read(KT/"M4NativeTools.kt")
        phone=read(JAVA/"PhoneController.java")
        screen=read(KT/"ChatScreen.kt")
        api=read(KT/"ChatApi.kt")
        self.assertIn("WhatsAppPlanner.parse(cleaned)",bridge)
        self.assertIn('whatsapp_draft:',parser)
        self.assertIn("prepareWhatsAppDraft",phone)
        self.assertIn("READ_CONTACTS",read(ROOT/"app/src/main/AndroidManifest.xml"))
        self.assertIn("RequestPermission()",screen)
        self.assertIn('"prepare_whatsapp_draft"',api)
        self.assertIn("لم تُرسل الرسالة تلقائياً",phone)
        self.assertNotIn("ModalBottomSheet(",screen)

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
