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

## Native coding, not a separate terminal app

Compose **تنفيذ كود** opens the built-in Arabic Terminal in the same APK. The launcher seeds a real editable offline M4 source checkout at `/root/projects/M4` and invokes the integrated `term-agent`; existing files are preserved. The agent understands the fixed selected source family and uses bounded tool-calling HTTP requests. Its **real seven tools** are `list_files`, `read_file`, `search_code`, `git_status`, `write_file`, approval-gated `run_command` and independently checked `finish`. It uses SQLite events, NewAl's verified continuity/checkpoint modules, backups, test checks and bounded resume.

The system prompt in `agent/system_prompt.md` is an original M4-specific adaptation of the publicly archived [Claude Code Opus 5.5 reference](https://github.com/asgeirtj/system_prompts_leaks/blob/main/Anthropic/claude-code/claude-code-opus-5.5.md), rewritten for real M4 tools and its mobile limitations, **not copied verbatim**. The model is a remote free route chosen at execution time, **not Claude Opus 5.5**, and a prompt alone does not upgrade reasoning capability.

Free chat may work without a key when one keyless provider is available. Coding automation needs a model that correctly supports tool calls; when none does, the task remains blocked, never falsely marked complete. Invoking native shell commands can alter or delete project files, and is **not a hardened sandbox**. Android may stop background processes: state is persisted, but indefinite execution is not guaranteed.

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


## v2.1 — Native chat agent tools and fast Arabic actions

Previous v2 chat requests were ordinary text-only completions and could misleadingly say it could not access the device. Starting v2.1, `ChatApi` **actually includes OpenAI-compatible tool definitions**. It accepts bounded streaming tool calls, passes them to `M4NativeTools`, returns real tool-role output to the model, and shows activity in chat. Non-tool-capable free routes remain a limitation. The chat **loads** `app/src/main/assets/m4-chat-system.txt`, an M4-specific condensed adaptation of the engineering workflow reference requested by the user. The separate Python coding agent still uses `agent/system_prompt.md`.

A direct command such as `افتح واتساب` or `افتح واتساب باستخدام الطرفية` bypasses the internet/model entirely. It uses the **same ArabicCommandRouter and PhoneController** that powers the existing terminal. The user should see an attempted Android application launch immediately (device install state/OS restrictions may still reject it).

For longer commands, the model can use `terminal_command`, which requires a **visible approval dialog for each command**, then runs inside M4's **actual Alpine PTY** managed by `TerminalService`, returning bounded stdout/stderr and exit status. It does not require switching to a terminal screen, Termux, ADB or Wi-Fi debugging. Initial Alpine unpacking can take time; actions are bounded to avoid hanging the phone. Tool-capable models can inspect files and Git this way, although arbitrary code execution is dangerous: review the requested command carefully.

Open the conversation drawer and select **ربط GitHub رسمياً**. M4 opens its own Arabic Terminal and starts `gh auth login --hostname github.com --git-protocol https --web`. The user must complete the official GitHub device/browser login. After authentication, `gh auth status` and user-approved `git`/`gh` commands work inside the Alpine runtime. No GitHub token is bundled, put into an LLM prompt, or requested through a manual custom API form. Use the on-screen terminal for the device code if automatic browser open is unavailable.

Latency optimizations: explicit phone actions need no network, route connection/first-token timeouts are shorter, failed routes cool down temporarily, output token rendering is batched in Compose, and the first response is checked for common Spanish intros when the user asked in Arabic. No guaranteed inference latency is claimed. Actual phone execution must still be checked on an Android ARM64 handset.

**GitHub Actions:** On main, only the Android APK workflow auto-runs and uploads `ArabicTerminal-debug-APK`; Python/Kotlin standalone checks still run for PRs or manual dispatch and have no APK artifacts. Open [M4 actions](https://github.com/Musabgpt/M4/actions/workflows/android.yml) to download a build; the generic 'All workflows' page also contains non-APK workflows.
