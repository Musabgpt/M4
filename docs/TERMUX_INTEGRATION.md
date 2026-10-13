# Optional Termux connector

The embedded Alpine/PRoot/PTY terminal is M4's default execution surface.
Termux is an optional Cursor workbench connector, opened from one button when a
user needs the separate Termux environment. M4 does not depend on it.

`TermuxClient` sends commands through the official `com.termux.RUN_COMMAND`
service and waits for the documented PendingIntent callback. Each command has a
bounded Termux working directory, explicit capability approval, a timeout, and
a durable receipt containing stdout, stderr and exit code. A dispatch without a
callback is reported as `pending_result`, never as success. The result is also
saved as a chat report artifact.

The device must have Termux installed, grant `com.termux.permission.RUN_COMMAND`
when requested, and enable `allow-external-apps=true` in Termux. Missing or
blocked Termux only disables this optional connector; it never changes the
embedded terminal. Android UID isolation and private app paths are preserved.
