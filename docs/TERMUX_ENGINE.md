# Termux agent execution engine

Extensions → Termux (or GitHub) → Enable for this conversation bootstraps Python,
Git, GitHub CLI, Node LTS and the official
`@modelcontextprotocol/server-filesystem@1.0.0`. Android's RUN_COMMAND permission
and Termux's `allow-external-apps=true` are required. M4 cannot grant these Android
or Termux permissions to itself. Termux remains optional; the existing embedded
runtime is available when it is not enabled.

The integration has no WebView, ttyd or embedded Termux screen. RUN_COMMAND starts
a Termux-owned Python service on an ephemeral loopback port. A random secret held
in each app's private storage authenticates every RPC; browser-origin requests
are rejected. A send receipt is not success: TermuxStatus must report ready.
Android may stop background processes; connection failures are shown explicitly.

The Agent receives `exec_command`, `write_stdin`, `read_output`,
`terminate_process`, `apply_patch`, TermuxReadFile/WriteFile/EditFile,
TermuxFilesystemTools/Call, and TermuxGitHubAPI. Commands default to separate
stdout/stderr pipes; `tty=true` creates a controlling PTY and merges the streams.
Results contain a real exit code, session id, cursor, truncation and pagination
flags. A null exit code means still running. Sessions belong to their originating
conversation. Stop/revoke kills that conversation's processes and MCP clients.

Filesystem tools communicate with the official server using a persistent stdio
JSON-RPC connection and negotiated initialization. The allowed root is Termux
`~/projects`. The bridge resolves relative paths and rejects escaping paths and
symlinks. These checks are file-tool and working-directory boundaries, **not an
OS sandbox for shell commands**. Explicit engine authorization grants the agent
Termux's filesystem permissions for that conversation. Known destructive shell
patterns and GitHub DELETE requests still ask for concrete approval; pattern
matching cannot prove arbitrary shell code safe. Plan mode remains read-only.

GitHub login uses `gh auth login --web`, with the user's browser/device-code
confirmation once. `gh auth setup-git` configures Git afterward. No custom OAuth
client id and no copied Codex credential is involved. GitHub API calls use `gh api`
in the same credential context; passwords/tokens are never returned by that tool.
Native GitHub/GitLab clients remain separate existing integrations.

Validation includes real PTY input/Ctrl-C, separate output and nonzero exit codes,
session ownership, bounded output cursors, patch preflight, loopback authentication,
and write/edit/read using the official npm Filesystem MCP process. Desktop
subprocess tests do not establish that RUN_COMMAND works on a physical phone;
that requires Termux installed and its permissions enabled on the device.
