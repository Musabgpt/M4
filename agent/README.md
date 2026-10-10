# Term Agent — AI coding environment embedded in Arabic Terminal

Term Agent is a Python-stdlib coding loop bundled in the app's Alpine ARM64 PRoot rootfs, not an external Termux dependency.
Python, Node/npm, Git, Bash, tmux, ripgrep and basic Linux developer utilities are installed during APK compilation.

## Setup inside the Android application

```sh
term-agent doctor
mkdir -p /root/projects/demo
cd /root/projects/demo
term-agent init
term-agent check-add 'python3 -m unittest discover -s tests'
export TERM_AGENT_API_BASE='https://your-provider.example/v1'
export TERM_AGENT_MODEL='your-tool-capable-model'
export TERM_AGENT_API_KEY='YOUR_KEY'
term-agent run 'Build and test a Python CLI' --steps 30 --allow-exec
term-agent status
term-agent resume --steps 30 --allow-exec
```

Use an API with an OpenAI-compatible chat-completions **tool-calling** interface. Local servers can be specified as `http://127.0.0.1:PORT/v1` if they are reachable.
No LLM model weights, model server or free inference credits are bundled. Do not commit API keys to source control.

## Persistence and safety

- `state.json` records goal, iterations and status; SQLite records tool calls, results and verification; backups are stored in `.term-agent/backups/`.
- `term-agent rollback path/to/file` restores the latest pre-edit snapshot of that file.
- Each run has a finite step budget (1 to 500) and repeated-action detection; `resume` continues the same goal.
- `finish` triggers independent verification commands from `term-agent check-add`; **no checks means no completed status**.
- `term-agent verify --allow-exec` runs checks manually.
- `--allow-exec` is required for subprocess execution. It permits arbitrary code execution via allowed interpreters (Python/Bash/Node). This is NOT a sandbox. Use on trusted projects only.
- The rootfs is Android app-private. PRoot is not a hardened permission barrier. The foreground service can still be terminated by Android; manual resumption is supported.

## Optional integrations

Run `term-agent tools` for installed/optional tools and `term-agent-addons help` for network installs:
`term-agent-addons pi`, `term-agent-addons ralph`, `term-agent-addons github`, `term-agent-addons python-tests`.
Pi/Ralph are third-party downloads; they aren't silently installed inside the APK. Their current Alpine/musl compatibility must be verified on a real device.
MCP servers, Tree-sitter, ast-grep, pytest, Vitest and ESLint are optional tools installed separately when compatible.
GitHub Actions is already configured for APK build and tests. Termux:API/Termux:Boot are not compatible drop-in packages for this standalone app.

Offline tests: `python3 -m unittest discover -s agent_tests -v`.

## Bundled developer tools and security

pytest is preinstalled. The signed Alpine package installer attempts to include
GitHub CLI, Tree-sitter CLI and ast-grep; if repositories lack a package, that
individual tool is left optional. Use `term-agent tools` to inspect availability.
`term-agent-watch 5 30` auto-resumes a goal for at most five 30-step runs.
It stops on completion, repeated failure, configuration errors or its run cap.
Code execution is opt-in when starting a goal; it is not isolated from other
app-private files. Commands are passed filtered environment variables to avoid
exposing API secrets by default.
