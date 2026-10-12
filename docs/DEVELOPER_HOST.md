# Optional Code-OSS host

Extensions → Code-OSS installs code-server 4.141.0 in the app's private Alpine
runtime and opens its authenticated web workbench. Termux is not required.
The native Agent and Files panels remain available; this is a separate editor
surface, not a claim that the native editor implements all VS Code APIs.

Installation downloads npm packages and builds native dependencies. It needs
additional storage, network access and several minutes. No completed/installed
state is inferred from a button press: the installer must exit successfully and
the server must serve its password login before opening the workbench. Failed
installation exposes the tail of the real process output and permits retry.

The host binds only 127.0.0.1, uses a random per-process password, and has no
Android JavaScript bridge. The app submits the password via POST, never a URL.
Stop terminates the owned process; leaving the panel retains it until stopped
or the Activity's ViewModel is cleared. Android can terminate background apps;
this is not an always-running background server.

The shared project is `/root/projects/M4`. Save native editor buffers before
editing the same file in Code-OSS; the native save conflict check still applies.
Code-OSS has its own extension manager using Open VSX. Language support is
provided by its built-in language services and installed compatible extensions.
Desktop binaries and proprietary marketplace extensions may be unavailable.
The existing free-model Agent and reviewed AI Complete action are native M4
features; this does not add Cursor's proprietary AI services to Code-OSS.

Verification must distinguish desktop Alpine installation from Android PRoot
startup, WebView editing, extension activation, and real language-server results.
A successful `--version` alone does not prove these latter behaviors.
