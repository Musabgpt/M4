# Current merged capability matrix

Supersedes the initial audit. Target: M4, branch `codex/m4-v2-integration`, PR #9.

| Capability | Status | Evidence / boundary |
|---|---|---|
| Normal chat tool loop, strict schemas, file edits, permissions, cancellation | implemented_verified | 74 Python behavioral tests; remote model fixtures are identified in tests |
| Real artifacts: md/html/txt/pdf/zip/py/sh/json | implemented_verified | Python byte/hash/CRC/syntax checks; Android emulator Arabic PDF rendering and ownership checks |
| Embedded Alpine/PRoot PTY in same Activity | implemented_unverified | Compiled APK includes runtime; full ARM64 phone execution needs device evidence |
| Durable background commands and HTTP readiness | implemented_verified | Real server survives runtime closure; owner isolation, HTTP response and termination tested |
| Nine scheduler types, persistence, approvals, event triggers | partial | SQLite emulator and JVM DST/window checks; elapsed hourly/daily device execution not yet measured |
| Owned WebView DOM interactions and test receipts | implemented_verified | Android emulator types Arabic, clicks, asserts DOM and captures actual pixels |
| Phone launcher, accessibility, SOS and test lab | partial | Launcher and denied-access behavior exercised on emulator; full authorized third-party app journey needs physical device |
| Optional official Termux connector | implemented_unverified | Real result intent adapter; compatible installed Termux and permission required |
| Skills and tested computation Tool Factory | implemented_verified | Actual script and expected-output tests, hot registration and removal |
| MCP stdio and Streamable HTTP | implemented_verified | Real subprocess and HTTP/SSE fixtures, schema checks, session recovery and owner isolation |
| Lifecycle hooks and read-only subagents | implemented_verified | Actual hook subprocess, deny behavior and child mutation rejection; write-capable specialist roles not provided |
| Multi-turn checkpoints and rewind | implemented_verified | Entire owner timeline restored; conflicting external/other-owner edits block all restoration |
| History/fork/edit/import/export, event cards, steering, previews | implemented_unverified | Native implementation; full device UI journey and prolonged load remain to be measured |
| GitHub device flow, Keystore, PR/CI/logs/artifacts | implemented_unverified | Native implementation; product OAuth client ID and user authorization required |
| Verified APK download and system installer | implemented_unverified | Hash/CRC/package/version/certificate guards; original signing key and actual install acceptance required |
| Provider profiles, repair, circuit health and vision probes | partial | Offline protocol checks pass; live provider evidence is separately recorded by CI |
| Root/ADB/Playwright on physical Android | unsupported_on_device | No authorized connected device; never advertised as available |

`implemented_verified` is limited to the evidence stated; it does not mean every Android end-to-end acceptance case passed.
