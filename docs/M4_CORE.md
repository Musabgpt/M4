# M4 Core (agent/m4core) — v0.1.0

Integrated Python package: `agent.py run` and `resume` now use the M4 core runtime by default.
The old seven-tool runner remains selectable with `--legacy`. Provider defaults retain
four families, while `providers.d` can be extended with verified entries.

## What is in
| Module | Purpose |
|---|---|
| `fs_tools.py` | Read (line numbers, offset/limit), Write (read-before-write + backup), Edit/MultiEdit (unique exact replace, atomic), Glob, Grep (ripgrep, Python fallback), ListDir, TodoWrite |
| `shell.py` | Bash with timeout + process-group kill, background shells, BashOutput, KillShell, head+tail truncation, secret scrubbing (`*_KEY/_TOKEN/_SECRET/_PASSWORD`, `M4_KEY_*`, `GH_TOKEN`...) |
| `prompted_tools.py` | Tool calling for models without native function calling: `<m4_call>` blocks, tolerant JSON repair, fenced fallback, unknown tools dropped |
| `router.py` | `providers.d/*.json` catalog, same URL safety rules as `provider_routes.allowed`, circuit breaker, capability cache (native/prompted/none), keyed providers only when their env secret exists |
| `loopguard.py` | Detects repeated identical calls and long read-only streaks (warn, then stop) |
| `compaction.py` | Deterministic context folding that keeps goal, touched files, last error |
| `permissions.py` | Modes ask / accept_edits / plan / auto; dangerous commands always ask |
| `registry.py` | Tool schemas + dispatcher applying permissions |
| `runtime.py` | Live `run`/`resume` loop, native/prompted provider fallback, compaction, guarded approvals, persistence, verification |
| `git_tools.py` | Read-only GitStatus, GitDiff, GitLog, public GitHubPR inspection; no push/merge |

## Providers
`providers.d/10` and `20` mirror the four existing families exactly (a test enforces the match with `provider_routes.ROUTES`).
`providers.d/30` lists keyed free-tier candidates **disabled by default** with model `CHECK-MODEL-AT-IMPLEMENTATION`. Verify limits, terms and model ids before enabling any, and fill `verified`.

## Running the integrated core
- `python3 agent/agent.py --project /path/to/repo run 'Implement goal' --steps 30` (edits accepted, Bash blocked)
- Add `--allow-exec` to authorize ordinary Bash commands; dangerous commands remain blocked by automated approval.
- `--permission-mode plan` blocks file edits, `ask` requires approval, `accept_edits` is default.
- `--permission-mode auto` additionally requires `--allow-exec`.
- `--legacy` opts into the old seven-tool runner. Use `tools` to inspect the live M4 tool schema.
- Set verification checks with `check-add` first; Finish does not count as success unless checks pass.
- Git/GitHub tools are currently **read-only**. No hidden git push or automatic PR merge.

## Not done yet (next phases)
- Subagents (Task), WebFetch/WebSearch, Git write actions and authenticated GitHub tools, `m4ctl`, Skills/MCP/Hooks.
- Android side: CapabilityBroker, Device Flow, Accessibility tool layer, UI.
- Nothing here was run on a handset or built into an APK; only the Python tests ran.
