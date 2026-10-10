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
