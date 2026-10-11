# V2 security boundaries

The tool registry validates types, required fields, unknown arguments and
bounded collections before execution. Native and strict JSON-envelope tool
calls share permission checks. Plan mode refuses mutations and execution.
Existing files require an unchanged read hash before Write/Edit/MultiEdit;
MultiEdit validates every replacement before a single atomic write and backup.

Secret-bearing file paths and security/signing/CI paths are unavailable to model
file tools. Grep denial globs are applied after caller inclusion globs. Runtime
protected paths are listed in android/PROTECTED_PATHS and in the runtime policy.
The private signing certificate/script and rootfs extractor are preserved.

Commands use a bounded allowlist and scrub common credential environment names.
User-authorized interpreters can still read app/project files: this is not a
hardened process sandbox. Provider URLs remain fixed, HTTPS remotely and
loopback-only locally. No OAuth or provider key storage was added in this phase.

The Android Unix bridge requires the same UID and a fresh random session token
held in app-private runtime configuration, excluded from model context and
events. Its actions are limited to capability discovery, native PDF generation
and creating disabled scheduling approval requests. It does not grant phone,
browser, Termux or GitHub access. File artifacts are hash-checked, URI sharing is
read-only and HTML previews have no native bridges or local/network access.

Process/state locks prevent concurrent agents on a project. Scheduled task locks
and unique due keys prevent replay, and interrupted side effects require review.
Model prose, file contents, DOM, memory and tool results never grant permission.
