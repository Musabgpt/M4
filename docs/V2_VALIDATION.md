# M4 / NewAl merge validation

The implementation covers the V2 workbench, runtime and integrations. Full Android acceptance remains incomplete where physical device, account authorization or original signing material is required. This report supersedes earlier first-gate reports.

## Observed evidence

- Current local Python suite: **74 tests passed**, including real HTTP server lifecycle, real MCP HTTP/SSE, MCP restoration, multi-turn rewind and subagent isolation.
- Signing-policy suite and compilation are run separately in CI.
- Prior merged commit `30d4969f0b9f645003a9c3336c7a9e95f68def79`: all four CI workflows succeeded. Android emulator evidence: https://github.com/Musabgpt/M4/actions/runs/38134388667 ; APK build: https://github.com/Musabgpt/M4/actions/runs/38134388655 . These links do not validate changes made after that commit.
- New commits must pass their own Kotlin/JVM, APK and emulator workflows. See PR #9 checks for the exact tested SHA. The live-provider artifact records **PASS or BLOCKED** independently of offline tests.
- Android evidence exercises owned WebView interaction, actual Arabic PDF page rendering, artifact tamper/owner rejection, persisted scheduler rows and launcher capabilities. An additional test rejects a development APK as an original-certificate update.
- No physical ARM64 phone or original private signing key is available. Host GitHub authorization is not a credential transferable to the Android app.

## Complete user acceptance journeys

The statuses below concern the full Android scenario, not just its passing components.

| Case | Status | Evidence still needed |
|---|---|---|
| E2E-01 Python from chat | BLOCKED | ARM64 Alpine chat-to-script/stdout/card journey; host real-process counterpart passes |
| E2E-02 Documents and restart | BLOCKED | Native save/share/reopen journey; real PDF rendering and byte validation pass |
| E2E-03 Interactive web project | BLOCKED | Full model-to-Android project journey; actual WebView interaction/screenshot component passes |
| E2E-04 Background server | BLOCKED | Android PTY observation; host persistent process + HTTP + stop passes |
| E2E-05 Termux | BLOCKED | Installed compatible Termux and granted RUN_COMMAND permission |
| E2E-06 Hourly CI task | BLOCKED | Product GitHub authorization and elapsed repetition on device |
| E2E-07 Daily scheduled tests | BLOCKED | Actual device scheduling/reboot evidence; calendar/DST unit checks exist |
| E2E-08 Phone test | BLOCKED | Authorized physical accessibility interaction and emergency stop |
| E2E-09 Product self-CI | BLOCKED | Product OAuth client ID and authorization; host PR is not product self-CI evidence |
| E2E-10 Dynamic skill | BLOCKED | Android journey; actual skill script/registration tests pass on host |
| E2E-11 Denied phone permission | BLOCKED | Combined phone-denial and ARM64 terminal journey; emulator denied-access guard tested |
| E2E-12 Network/UI interruption | BLOCKED | Android kill/reopen; durable command restoration tested on host |
| E2E-13 Injection/security | NOT_TESTED | Full adversarial website journey; schema, secret, origin, ownership and plan-mode component tests pass |
| E2E-14 Single Activity | BLOCKED | Full ARM64 build/test/navigation journey |
| E2E-15 10,000 events / 30 minutes | NOT_TESTED | Physical memory/ANR/thermal and cancellation measurements |

## Permissions and release limits

Added explicit Termux RUN_COMMAND integration and Android REQUEST_INSTALL_PACKAGES for user-confirmed verified updates. Added non-exported result receivers and narrowly scoped package visibility. No QUERY_ALL_PACKAGES, root grant or screen-protection bypass is added. The pinned certificate and original release-signing policy remain enforced.

The development APK uses `dev.newal.code.lite.connectors.m4dev` and installs alongside the original. Updating the original package requires its private signing key in the repository's existing signing secret. An OAuth application's client ID is public configuration, but it cannot be derived from this session's GitHub connector.
