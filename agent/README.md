# M4 native Arabic Terminal coding agent

The agent executes inside bundled ARM64 Alpine/PRoot, with M4's native PTY. No Termux, external plugin manager, manual API editor or local model importer is involved.

Seven actual tools: `list_files`, `read_file`, `search_code`, `git_status`, `write_file`, user-authorized `run_command` and independently verified `finish`. Prompt: `system_prompt.md`. Fixed inference catalog: `provider_routes.py`. The native Android launcher selects a gateway family and starts `m4-agent-start`.

FreeLLMPool contributes anonymous HTTPS upstream routes, subject to availability/rate limits. GPT4Free, Free LLM Gateway and FreeLLMAPI are compatibility adapters for genuine local servers, not falsely described as bundled hosted services. An unavailable local server falls back to vetted keyless routes; a model lacking tool calls cannot complete a coding task.

The worktree and backups persist locally; command execution is powerful and opt-in, not a hardened sandbox. Term Agent uses project tests and bounded checkpoints.
