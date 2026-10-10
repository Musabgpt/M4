# M4 — MusabAI + Term for Android

A standalone Android application combining **Term v0.11.1** (native Android PTY + bundled Alpine ARM64/PRoot + coding agent) and selected, verified **NewAl-Cloud** capabilities, presented in the supplied Kotlin Jetpack Compose chat UI.

## Verified source selection

- `Musabgpt/Term` commit `579a96cb98ab997a4131a6212a515b55f5b37dd2`, successful build on October 10, 2026.
- `Musabgpt/NewAl-Cloud` commit `d34858159aeb71c24b6d01aaba5a7a717fb33e73`, its latest advanced successful CI on October 9, 2026. NewAl-Cloud's later main branch was deliberately reset to the older Action 125 baseline, so M4 pins this known-green advanced commit instead.
- User-provided Kotlin UI references: `ChatScreen.kt`, `ChatViewModel.kt`, `ChatApi.kt`, adapted into `app/src/main/java/com/musabgpt/m4/`.

## What works

- Kotlin Compose Arabic chat with sidebar, streaming SSE, stop, session history, model/provider picker and settings.
- Default NewAl keyless Kilo Auto Free route (subject to provider availability), optional user-supplied Groq/Gemini/OpenRouter/custom API credentials, and fallback to configured providers on connection failures.
- **تنفيذ كود** opens the built-in Term Android terminal and starts `term-agent` on the entered objective in `/root/projects/M4`. No separate Termux app or wireless debugging is required.
- Native Android terminal, Alpine Linux, Python, Node/npm, Bash, Git, tmux, pytest, GitHub CLI, Tree-sitter, ast-grep, agent history in SQLite, backups, verification and bounded resume.
- Real NewAl-Cloud `continuity` and `task_state` modules integrated with the Term coding loop. NewAl task checkpoints only mark complete *after* verification.
- App ID `com.musabgpt.m4`, separate from Term's original ID so existing Term installations remain untouched.

## Important limits

**This is a selective code integration**, not a claim that the entire NewAl Python engine, every MCP connection, and every cloud integration was migrated. NewAl's full Android native engine depends on a different PRoot/Python/server distribution and build pipeline. The M4 coding agent uses Term's existing Linux engine; only verified portable NewAl components are wired in. Some Pi/Ralph integrations need an optional network install.
No model weights, universal free inference, unbounded loops or unlimited API requests are included. Kilo's availability and tool-calling capability are controlled by its service; a fallback model must support tool calls for coding automation.

Use the cloud chat without configuring a key when the free endpoint is available. The coding agent receives the selected model configuration through an app-private shell environment file. If the model cannot return tool calls, the agent stops with a diagnostic rather than pretending completion.

Android foreground services cannot guarantee indefinite activity. `term-agent resume` and `term-agent-watch` continue from saved state when a process survives or is restarted.

**Safety:** The coding agent can run project commands. The UI asks for confirmation first, but `--allow-exec` grants broad interpreter access; use trusted projects and make backups. Provider API keys are kept in app-private preferences/files, not committed. The model provider receives the submitted text. Real-phone testing is required beyond a successful CI build.

## Build

GitHub Actions workflow `android.yml` generates a debug APK embedding verified Alpine + PRoot. A lighter `m4-kotlin.yml` checks compilation without the Linux bundle.
The debug signing key may change between runs. Export any important app-private projects before reinstalling.


## v1.0.1 — Real M4 project execution

The Compose **تنفيذ كود** button now calls bundled `m4-agent-start` rather than creating an empty workspace. On first run the command copies the verified M4 source snapshot embedded in the APK into `/root/projects/M4` (or clones the public repository if the snapshot is unavailable); later runs **preserve local modifications** and reuse that checkout without an automatic pull/reset. A pre-existing non-checkout directory fails safely instead of being erased. The launcher configures `python3 -m unittest discover -s agent_tests -v` as real project verification, and the agent only reports completion after successful checks. Building the Android APK still requires GitHub Actions/Android SDK; Python tests do not prove Android correctness. User API/provider selection is forwarded using the private `provider.env` written by the app.

The offline seed is a new local Git baseline, not a reproduction of the remote Git history. To work with the latest upstream changes, connect and review `git fetch origin` followed by an appropriate merge; the launcher never resets or force-pulls user changes.


## v1.0.2 — Agent progress in Compose

The chat app now has an **حالة الوكيل** (agent status) action. It reads the real app-private `/root/projects/M4/.term-agent/state.json` from the installed Alpine environment and displays the saved goal, phase, step count and verification summary. A manually triggered **استئناف** action can continue a task that paused at its iteration limit; active or blocked tasks are not automatically duplicated. The view is an on-demand snapshot with a Refresh button, not a claim of live backend streaming.


## v2 update signing: exact NewAl-Cloud identity, fail-closed

NewAl-Cloud's **last advanced persistent release** (run 37916175916, build 363) used package `dev.newal.code.lite.connectors` and verified SHA-256 signing certificate `6af53b6b3e2574eed16aaae7ce92ade3dcde702a74af1dfae994c9c87609cbb6`. M4's **release** variant targets that same package with versionCode 10000+; the separate debug variant has a distinct `.m4dev` package suffix and cannot overwrite the release. To produce an actual update, the owner must make the **original identical signing keystore** available as the GitHub Actions secret `MUSABAI_RELEASE_SIGNING_BUNDLE` in the M4 repository. The workflow reads it only in runner temporary storage, checks the signed APK's certificate against `android/signing-cert-sha256.txt`, and refuses to publish a mismatched build. A certificate fingerprint alone cannot sign an APK. Existing `dev.newal.code.lite` baseline apps and existing `com.musabgpt.m4` apps have *different package IDs* and therefore cannot be updated in place by this package; backups/migration would be needed. No release secret is copied from NewAl-Cloud automatically.
