# V2 implementation and acceptance evidence

This delivery implements the first workbench gates from the supplied V2 roadmap.
It does not establish completion of the entire roadmap. The repository was
audited at remote `56de7a901fc22a1b2e6d374800d73bb6b131bf2c`; changes are in
`codex/m4-v2-integration`, draft PR https://github.com/Musabgpt/M4/pull/9.

## Verified development evidence

- Original regression: 26 agent tests and four signing-policy tests passed.
- V2 offline tests: 48 agent tests and four signing-policy tests pass. Coverage
  includes exact reads/atomic edits, unchanged-read checks, permission
  modes, strict prompted envelopes, real text/ZIP artifacts, hash tampering,
  secret exclusions, bounded processes, cancellation, timeouts, task state,
  semantic loop detection, exclusive project ownership and interrupt recovery.
- Python compile and shell syntax checks pass. Existing signing pin, release
  signing script and SafeRootfsExtractor bytes are preserved.
- First integration commit `3501d905dfa0382903e812a8afa64f465b657ce1` passed
  all three workflows: agent run 38125249913, Kotlin/Java run 38125249814,
  Android SDK/NDK/rootfs/APK run 38125249822. The last produced the real
  `ArabicTerminal-debug-APK` artifact (109,777,361 ZIP bytes), artifact ID
  11696649531. Final refinement results are linked on the PR; an earlier
  build does not validate later source edits.
- Four additional JVM tests cover URL boundaries and DST/missed schedule
  windows; the final CI runs `:app:testDebugUnitTest` as well as compilation.

No physical device, emulator, authenticated ADB, optional Termux, Accessibility,
OAuth application configuration or private signing key was supplied. CI APK
construction is evidence of construction, not evidence of device behavior.

## Post-implementation capability status

| Capability | Status | Evidence and remaining prerequisite |
| --- | --- | --- |
| Strict registry, exact tools, Todo, process pool | implemented_verified | Offline behavioral tests, real subprocess output |
| Native + prompted JSON call validation | implemented_verified | Registry tests; live provider uptime is not guaranteed |
| Plan mode, memory, compaction, loop recovery, locks | implemented_verified | Python tests; memory remains untrusted data |
| Embedded terminal in launcher Activity / events | implemented_unverified | CI compilation; device PTY/ANSI/rotation acceptance required |
| UTF-8 / ZIP artifact generation and persistence | implemented_verified | Actual files, syntax, ZIP CRC and SHA-256 tests |
| Arabic PDF, native bridge, card preview/save/share | implemented_unverified | Android implementation compiled; device rendering/export required |
| WorkManager / SQLite task execution and approval | implemented_unverified | Android compilation; repeat, restart, stop and lock acceptance required |
| Daily local-time calculation | implemented_unverified | JVM test source added; see final CI result |
| File editor/diff and owned-WebView DOM | implemented_unverified | Compiled workbench; device interaction required |
| Existing manual phone controls | partial | Preserved; model bridge and phone test lab missing |
| Termux optional backend | missing | No permission/verified installed app/result adapter |
| Skills, MCP, subagents, hooks, tool factory | missing | No executable implementation in this delivery |
| Product GitHub device flow / credential vault / self-CI | missing | The host's GitHub connector does not register these inside M4 |
| Full permission dialog / approval steering / rewind UI | partial | Plan/execute switch, task-scope approval and file review work; full gate remains |
| Signing and rootfs protections | implemented_verified | Existing policy tests and first Android CI; release key still required |

## Full user acceptance cases

All cases below refer to the Android application's complete requested journey.
Partial desktop evidence does not mark the full Android scenario PASS.

| Case | Status | Required evidence / blocker |
| --- | --- | --- |
| E2E-01 Python from chat | BLOCKED | ARM64 device; generated script, actual stdout/exit code and persistent card |
| E2E-02 Eight formats / restart | BLOCKED | Device PDF rendering, HTML preview, ZIP CRC, export hash and reopened cards |
| E2E-03 Interactive web app | NOT_TESTED | DOM action assertions/screenshots bridge is not implemented |
| E2E-04 Embedded background server | BLOCKED | Device PTY/server HTTP observation and termination; process unit subset passes |
| E2E-05 Termux | BLOCKED | Optional connector + compatible installed/authorized Termux |
| E2E-06 Hourly CI check | BLOCKED | Durable device run history and configured GitHub command access |
| E2E-07 Daily tests | BLOCKED | Actual device WorkManager window, DST/reboot evidence and report |
| E2E-08 Phone test | BLOCKED | Phone bridge/test lab, authorized service and physical assertions |
| E2E-09 Product self-CI | BLOCKED | Product GitHub authorization/vault and CI loop; host PR is not that product feature |
| E2E-10 Dynamic skill | NOT_TESTED | Skills runtime not implemented |
| E2E-11 Refused phone permission | BLOCKED | Device refusal plus unaffected real terminal/artifact behavior |
| E2E-12 Network/UI interruption | BLOCKED | Device kill/reopen and durable result; host locks/cancellation tests pass |
| E2E-13 Injection/security | NOT_TESTED | Full malicious-page journey; strict tool/path/secret unit checks pass |
| E2E-14 One Activity | BLOCKED | Actual on-device chat/terminal/files/task/browser interaction |
| E2E-15 10,000 events / 30 minutes | NOT_TESTED | Device memory/ANR/cancellation measurements |

See CHAT_ARTIFACTS.md, SCHEDULER.md, BROWSER_AUTOMATION.md, SECURITY.md,
PHONE_TESTING.md and TERMUX_INTEGRATION.md for behavior and precise limits.
