# M4 V2 capability audit

Audited source: `Musabgpt/M4`, default branch `main`, remote commit/tree
`56de7a901fc22a1b2e6d374800d73bb6b131bf2c` (11 October 2026).
Git HTTP was unavailable in the development environment. The 69 files were
fetched through the GitHub connector and independently checked against their Git
blob hashes; the local Git baseline is a reconstructed source snapshot.

Status vocabulary follows the supplied specification. A desktop unit test is
evidence for that component, never evidence of Android/device behavior.

| Capability at audit | Status | Evidence / limitation |
| --- | --- | --- |
| Embedded Alpine / PRoot / JNI PTY | implemented_unverified | NativePty, TerminalService, LinuxEnvironment; no Android device attached |
| Multiple terminal sessions / SIGINT | implemented_unverified | TerminalService and MainActivity; device test required |
| Chat and terminal in one Activity | missing | M4ChatActivity launches MainActivity with an Intent |
| Seven coding tools / bounded requests | implemented_verified | 26 existing Python tests pass with writable NEWAL_TASK_STATE_HOME |
| Independent Finish checks / rollback | implemented_verified | Existing agent tests and NewAl continuity tests |
| Structured tool registry / exact edits | missing | Only seven hard-coded schemas in agent.py |
| Prompted JSON tool calls | missing | Text-only providers immediately fail over |
| Persistent conversation text | implemented_unverified | SharedPreferences history; no attachments or runtime event cards |
| Chat artifacts / PDF / safe export | missing | No artifact store, validators or provider |
| Persistent scheduler | missing | No WorkManager, scheduling database or executor |
| Phone launcher / Accessibility actions | partial | Existing PhoneController / UiControlService; no agent bridge |
| Browser workbench | missing | No WebView in chat |
| Termux official connector | missing | Optional; absence must not disable embedded Alpine |
| Skills / MCP / hooks | missing | No operational registry |
| GitHub device authorization / CI loop | missing | Optional gh executable; no credential broker |
| Package / signing policy | implemented_verified | Four existing signing-policy tests pass; private signing key unavailable |
| Rootfs extraction safeguards | implemented_unverified | Existing extractor tests; Android build not available locally |

Baseline commands: `NEWAL_TASK_STATE_HOME=/tmp/m4-baseline-tasks
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s agent_tests -v`
(26 passed), and `python3 -m unittest discover -s tests -p 'test_*.py' -v`
(4 passed). The initial default-home test failed because `/home/agent` is read
only in this environment; using the module's documented state-home setting
resolved that environmental failure without changing the module.

The completed implementation and device acceptance evidence are recorded in
`V2_VALIDATION.md`. Do not infer that all V2 acceptance gates have passed.
