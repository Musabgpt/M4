# M4 Core (agent/m4core) — v0.1.0

Additive Python package implementing Phase 1-2 of the M4 developer prompt.
`agent/agent.py` and `agent/provider_routes.py` are intentionally untouched, so
the existing 26 tests and the fixed-four-families guarantee still hold.

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
| `registry.py` | JSON schemas for 12 tools + dispatcher applying permissions |

## Providers
`providers.d/10` and `20` mirror the four existing families exactly (a test enforces the match with `provider_routes.ROUTES`).
`providers.d/30` lists keyed free-tier candidates **disabled by default** with model `CHECK-MODEL-AT-IMPLEMENTATION`. Verify limits, terms and model ids before enabling any, and fill `verified`.

## Not done yet (next phases)
- Wiring `m4core` into `agent.py`'s loop (router + prompted fallback + loop guard + compaction).
- Subagents (Task), WebFetch/WebSearch, Git/GitHub tools, `m4ctl` bridge, Skills/MCP/Hooks.
- Android side: CapabilityBroker, Device Flow, Accessibility tool layer, UI.
- Nothing here was run on a handset or built into an APK; only the Python tests ran.
