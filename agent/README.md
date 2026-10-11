# M4 native Arabic Terminal coding agent

M4 runs inside the app's bundled ARM64 Alpine/PRoot using the native integrated PTY. No external Termux window or local model download is required.

The default `run` and `resume` commands invoke `m4core.runtime` with Read, Write, Edit, MultiEdit, Glob, Grep, ListDir, Bash, BashOutput, KillShell, TodoWrite, GitStatus, GitDiff, GitLog, GitHubPR and Finish. The earlier seven-tool loop remains available through `--legacy`.

The live runner provides:
- Native function calls and text-only model fallback via structured `<m4_call>` blocks.
- Provider selection through `providers.d`, a health circuit breaker, and bounded requests.
- Loop detection, context compaction, SQLite event logs, NewAl task checkpoints, backup/rollback, and independent project verification.
- Permission modes `ask`, `accept_edits` (default), `plan` and `auto`. Shell execution requires `--allow-exec` (and dangerous commands are not automatically approved).
- Read-only local Git inspection and public GitHub PR lookup. No GitHub write credentials, auto-push or auto-merge.

**Run**: `python3 agent/agent.py --project /path/to/worktree run "your goal" --steps 30 --allow-exec`

**Verify**: `python3 agent/agent.py --project /path/to/worktree check-add "python3 -m unittest discover -s agent_tests -v"`

Run configured checks before marking a task complete. Code execution is powerful and *not* an isolation sandbox. Free inference services may change limits or availability without notice; never assume unlimited use. No APK or phone integration is proven by these Python tests alone.
