# M4 V2 implementation plan

The supplied developer specification is an incremental roadmap. Its latest
sections 18–30 define the gate order. The runtime prompt is application data;
tool names in it do not register capabilities.

1. Preserve and audit the current source, signing identity, backups, rootfs
   extraction, provider allowlist and CI. Record independent baseline results.
2. Bind the existing TerminalService to the Compose launcher. Show its actual
   ANSI frame, session controls, input and interrupt inside the chat Activity;
   launch the real agent in a dedicated Alpine session and retain progress.
3. Add a strict, permission-aware agent registry, safe exact file editing,
   task tracking, real process output and validated artifact storage. Load the
   V2 runtime prompt with the real schemas and environment description.
4. Connect published artifacts to persistent chat cards, safe previews,
   content URI sharing and the system document export picker. Generate Arabic
   PDF through Android's native text/layout engine, with independent byte checks.
5. Add a durable WorkManager task database, bounded Alpine execution, overlap
   protection, actual run output and task management. Clearly label timing as
   an execution window and preserve the owner's conversation.
6. Add secure owned-WebView observation and a project file/diff workbench,
   then capability-gated phone and optional external engine integration.
7. Validate Python behavior, Android compilation, signing and archive defenses
   without weakening mandatory CI. Physical-device, authentication and signed
   release gates remain blocked until their actual prerequisites are available.

Acceptance is tracked per supplied E2E scenario as PASS, FAIL, BLOCKED or
NOT_TESTED. Preserve source edits independently of remote publishing. No merge,
installation or release signing is inferred from the repository URL.
