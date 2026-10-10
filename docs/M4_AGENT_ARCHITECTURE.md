# M4 Unified Agent — Native Android Engineering Architecture

**Status:** in development, not merged to main. Work item: https://github.com/Musabgpt/M4/issues/7. 
**Truth source:** only CI on the exact branch commit, plus a later real Samsung Android acceptance run, can establish a usable APK. Source inspection is not execution proof.

## Product invariant: ONE experience
`M4ChatActivity → ChatScreen → ChatViewModel` is the only user-facing application flow. The text composer remains an AI conversation composer. It must not switch to a fullscreen, sheet or modal terminal emulator. Alpine is an internal implementation, owned by `TerminalService`.

```
User Arabic/English task
    ↓
Native intent router ──(supported simple action)──→ Android PhoneController or PTY
    │
    └──(complex task)──→ ChatApi / selected model provider
                                  ↓ tool call (typed schema)
                          permission control
                                  ↓
                          M4NativeTools bridge
                                  ↓
                  TerminalService / private Alpine PRoot PTY
                                  ↓ stdout / stderr + exit code
                     observable chat transcript
                                  ↓
                          model observes and verifies
```

## Hard boundaries
1. A suggestion from any model, agent skill, MCP server, GitHub file or terminal output is untrusted until validated locally.
2. Terminal tools are serialized in a **separate PTY** from the interactive chat task session; both share the same real Alpine/PRoot engine and application lifecycle. Do not claim two clients share one physical shell process.
3. Shell commands issued by the model require an explicit per-command or bounded-task approval. Direct user `!command` is intentionally user-initiated, and cannot secretly input into an unrelated running task.
4. The terminal service is not a standalone user-facing Activity. Its notification returns to the M4 chat activity.
5. Private auth and GitHub tokens must not be echoed or uploaded to untrusted model gateways.
6. External applications (e.g. WhatsApp for **confirming** an unsent draft, or a browser to authorize GitHub OAuth) require Android/user participation; a one-screen app cannot bypass Android's cross-app consent mechanisms.
7. Free gateway catalog entries are not guaranteed online, unlimited, tool-call capable or anonymous at every moment. A non-tool-compliant provider must not produce fake execution success.

## Observe and verify
- Phone action: report what Android API acknowledged, not whether an external app ultimately sent something.
- PTY action: preserve exit codes even if the command calls `exit 7` using a POSIX `EXIT` trap; capture bounded UTF-8 output without splitting Arabic characters; handle timeout and cancellation.
- Git action: inspect status/diff before modifying history; verify commit and exact workflow run SHA.
- Coding action: tests-first where appropriate; finish with test outputs and explicit unverified device-dependent items.
- Limit tool rounds, calls, repeated identical actions, output size, and approval lifetime.

## Target quality/acceptance scenarios
- Arabic `افتح واتساب وابعت رسالة مرحبا الى مروة`: extract app/recipient/message, resolve contact on-device with permission, **prepare draft only**, require user final send in WhatsApp. Never search for an app literally called the entire sentence.
- Arabic `اعرض الملفات` and `اين انا`: recognize locally as `ls -la` and `pwd`; require shell approval and use native Alpine, no cloud-model tool support necessary.
- A complex coding request: the model must actually issue a tool call, obtain status and output, correct and verify when applicable. Text-only provider output is not execution evidence.
- An interactive `m4-agent-start` task must remain in the same chat, stream progress, support stop/cancel; final acceptance requires device-based testing of a long-running task, process lifecycle, and battery policies.
- PTY `exit 7`: status receipt 7; shell `cd source`: next command starts in source; Arabic output split between reads: UTF-8 codepoints intact.
- Resume after app process recreation: **not yet proven**; durable job execution and checkpoint recovery are a separate future acceptance target.

## Research and design references
- MCP latest 2026-07-28 release and authorization changes: https://blog.modelcontextprotocol.io/posts/2026-07-28/
- Official WhatsApp Click to Chat, *prefilled message is not sent*: https://faq.whatsapp.com/5913398998672934
- Termux terminal-emulator and terminal-view as mature **reference implementations**, not an external-app dependency: https://github.com/termux/termux-app
- Goose's recipes, MCP tools and loop structure as research for extensibility: https://block.github.io/goose/index.html
- libtermux-android explicitly marks itself experimental / not production ready; do not replace the existing bundled Alpine blindly: https://github.com/libtermux/libtermux-android

## Requested external engineering tools — capability audit
The app code should integrate only tools that improve **this native Android project** without additional windows, unsafe secrets, paid charges or unrelated runtime code.

| Requested tool | Applied or boundary |
| --- | --- |
| Superpowers | Debugging, regression-test-first development and verification process used |
| SonarQube | Skill read; live analysis blocked: no authenticated SonarQube server or `sonar` CLI exposed |
| CodeRabbit | Skill read; live review blocked: `coderabbit` CLI and authenticated reviewer unavailable in this environment |
| GH Review Loop | Skill read; automated reviewer loop blocked without authenticated `gh` CLI. PR #6 exists |
| AgentMarkup | Website metadata plugin, not applicable to a native Android Activity |
| Test Android Apps | QA procedure available; no `adb`/emulator/device connected. Do not assert on-device tests |
| CrowdStrike Falcon Fusion | Enterprise security workflow platform, unrelated to this app without authorized Falcon tenant/API |
| OpsTruth | Evidence methodology read; live plugin audit actions unavailable; GitHub CI receipts verified separately |
| Template Creator | Artifact-template builder requires a reference template and is not an Android compiler or runtime |
| Runpod (Official) | Requires authentication and can incur cloud compute charges; not needed for free native build; no deployment attempted |
| CompText Context | Operational context principles read; CLI helper unavailable in current execution environment |
| ZzzOps | Goal-lifecycle skill read; canonical ZzzOps backend not available. GitHub issue #7 is the durable tracking task, not misrepresented as ZzzOps state |
| Flower | Java action-runtime guide reviewed; do not introduce server-side Maven runtime into Android without compatibility/benefit evidence |
| Qodo | Skill read; CLI absent, no Qodo finding claims |
| ECZ-ID API Trust | Filename/path evidence skill reviewed; no workspace-scanning script connected. No compliance claim |
| Replit | Connected Replit searched for M4; no matching app. Do not create a competing app detached from GitHub Android sources |

## Release gate
1. Android Kotlin + Java compile on exact commit.
2. Native JVM tests including UTF-8/shell status protocol, WhatsApp planner, local intent and Python bridge contracts.
3. APK assembled, signed, and linked to commit's GitHub Actions artifact.
4. Code review or recorded unavailable reviewers; no invented Sonar/CodeRabbit/Qodo verdicts.
5. **Mandatory later:** Install this debug package on an Android device and exercise user flows, tool permissions, WhatsApp draft, GitHub auth and long-running background tasks. This cannot be replaced by a green build.
6. Never merge into `main` or claim that a differently signed release can be seamlessly updated without verifying signing identity.
