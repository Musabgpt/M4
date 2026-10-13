# Merge provenance and decisions

M4 remains the Android/Compose and embedded Alpine base. NewAl-Cloud was reviewed at `9794eee7d124013b7d425c124581a787cc0efb19`. Its external NewAl engine was reviewed at `abfa91ee0644b27ba1957567ab484d4073d71efd`.

- Retained M4's native PTY, verified Alpine distribution, Arabic terminal, protected extraction and original-certificate policy.
- Ported NewAl-Cloud's MemoryStore into `agent/m4core/memory.py`; excluded autonomous shell self-modification.
- Ported the NewAl engine's repetition Breaker into `agent/m4core/circuit.py`.
- Adapted session history/fork/edit, checkpoints, lifecycle hooks, phone control and optional Termux concepts to real Compose/native tools and Python runtime contracts.
- Implemented fresh native GitHub authorization/vault, scheduler, owner-bound artifacts, WebView tests, official Termux callbacks and package update verification.
- Replaced empty bridge fallbacks with capability-based registration and observed error/blocked results. A UI label or model claim is never a substitute for a tool receipt.
- MCP and computation extensions must negotiate/register/test before use. Specialist subagents currently run bounded read-only tasks.

This is a functional integration, not a wholesale copy of the older JavaScript UI or a claim that its whole external desktop engine runs on Android. See CAPABILITY_MATRIX.md and V2_VALIDATION.md for measured scope and remaining device acceptance.
