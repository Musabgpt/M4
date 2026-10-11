# M4 v2 — MusabAI + Arabic Terminal

A native Android ARM64 app with the user-supplied warm-paper/dark Compose chat design, **one integrated Arabic Terminal** (Alpine Linux + PRoot + PTY), and a bounded AI coding agent. Everything in this repository is authored and built from **Musabgpt/M4**; upstream source repos are only references.

## One interface, fixed source families

The chat model selector contains **exactly four fixed gateway projects**. There is **no local GGUF/LLM importer**, no custom endpoint form, no manual API key input, no Termux bridge and no old Pi/Ralph plugins.

| Source project | M4 integration | Honest availability |
| --- | --- | --- |
| [0xzr/freellmpool](https://github.com/0xzr/freellmpool) | Built-in, audited keyless OpenAI-compatible upstream route catalog: Kilo, LLM7, Pollinations, OVHcloud; bounded HTTPS SSE + fallback | Works only when an external free provider responds; quotas and privacy vary; M4 embeds compatible routing logic, not the entire upstream Python distribution |
| [xtekky/gpt4free](https://github.com/xtekky/gpt4free) | Fixed loopback adapter for GPT4Free's OpenAI-compatible API at 127.0.0.1:1337 | GPT4Free backend is **not included**; it may need browser-based auth or heavy Python dependencies |
| [MrFadiAi/free-llm-gateway](https://github.com/MrFadiAi/free-llm-gateway) | Fixed loopback adapter at 127.0.0.1:8080 | Its code was not accessible through live GitHub API during integration; its upstream typically needs provider keys. Not presented as a hosted anonymous service |
| [PryxIntel/free-llm-api](https://github.com/PryxIntel/free-llm-api) | Fixed loopback adapter at 127.0.0.1:3001 | PryxIntel runs a Node/SQLite gateway which may require its own configuration and credentials; not shipped as a running server |

Selecting an unavailable loopback family transparently falls back to FreeLLMPool-approved anonymous routes. The chat shows the *actual route* used; a label in a list is never proof that its server is running. Providers are not guaranteed to be free forever, keyless forever, or to allow unlimited requests. User messages are transmitted to the active external gateway; do not send secrets through public free routes.

## V2 workbench and runtime

Chat, the existing Alpine/PRoot/PTY terminal, project files, durable tasks and an owned browser share the Compose launcher Activity. `!command` submits an explicit manual terminal request; **تنفيذ كود** starts the real coding agent in its own PTY session. `/plan` selects a mode which refuses model mutations/execution, `/permissions` displays the mode and `/help` lists supported commands.

The APK seeds a real editable offline M4 checkout at `/root/projects/M4` and preserves existing projects. The agent loads the supplied M4 runtime V2 prompt with its live registry, environment and project memory. It provides exact atomic file editing, strict schemas, Todo state, bounded foreground/background processes, incremental output/cancellation, backups, independent Finish checks and exclusive project ownership. Native calls and valid complete prompted JSON envelopes share the same permission checks. Models which provide neither remain blocked.

Actual generated text, JSON, Python, shell and ZIP files are validated and published as persistent chat cards. Arabic PDF uses the authenticated Android renderer when the bridge is live. Cards offer safe preview, open, system-picker save and temporary-URI sharing. The Tasks panel creates approved once/hourly/daily WorkManager jobs with SQLite locks, real outputs and Markdown reports. Android may defer background work; times are expected windows. Live tool events retain their owner conversation.

The host integration has passed agent, Kotlin/Java and APK CI on the first implementation commit. Physical-device PDF/PTY/restart/scheduling behavior still requires acceptance. Optional Termux, product GitHub OAuth/self-CI, phone/browser agent automation, skills/MCP/hooks and the full interactive approval/steering roadmap remain unimplemented or partial; the exact evidence is in [docs/V2_VALIDATION.md](docs/V2_VALIDATION.md). The prompt alone does not register those capabilities or guarantee model quality. Authorized interpreters are not a hardened process sandbox.

## Android build and update signing

Two kinds of APK deliberately have **different Android package identifiers**:

- **Development APK**: `dev.newal.code.lite.connectors.m4dev` — GitHub Actions `ArabicTerminal-debug-APK` for testing; uses ordinary debug signing. Cannot replace the old NewAl-Cloud app or an existing `com.musabgpt.m4` install.
- **Production signed update**: `dev.newal.code.lite.connectors` — same package as NewAl-Cloud's *advanced connectors release* (CI run 37916175916, versionCode 363), versionCode 10000 + Actions run number; produced **only** if M4 GitHub Actions has the original secret `MUSABAI_RELEASE_SIGNING_BUNDLE`.

The public certificate SHA-256 pinned at `android/signing-cert-sha256.txt` is:

`6af53b6b3e2574eed16aaae7ce92ade3dcde702a74af1dfae994c9c87609cbb6`

A certificate fingerprint **is not a private key**. A matching keystore and alias/private password must be supplied through the M4 repository's encrypted Actions secret. `scripts/build-signed-release.sh` decodes the bundle in the runner temporary directory, signs and **verifies the actual release APK certificate** against that pin, rejects any mismatch, and removes the keystore at job exit. If the secret is unavailable, the workflow publishes **only the separate development APK**, never a falsely signed update.

The earlier NewAl-Cloud baseline package `dev.newal.code.lite` and standalone M4 `com.musabgpt.m4` have different application IDs; Android cannot update either in-place from this advanced-connectors variant. Do not uninstall an existing app with private projects until you have exported a backup and confirmed which package is installed.

## Validation / limitations

CI runs Python agent tests, Kotlin/Compose compilation, rootfs integrity and native build tests, and Android SDK/NDK debug APK construction. A successful CI build is not proof of on-device UX or free-route uptime. APK updates preserve old private files only when Android sees the **exact package and signer** match; M4's Linux runtime upgrades preserve user folders and task history. Signed release builds depend on the original private key, which cannot be retrieved from the public source repository or reconstructed from its certificate fingerprint.

This distribution does not bundle local model weights, require root or ADB, give every gateway unauthorized access, or claim unlimited free service.
