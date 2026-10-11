# M4 — Termux-only migration (NewAl-derived)

## Intended architecture
User requirement: Remove native "Arabic Terminal" UX and floating overlay; Termux installed on the device owns Linux execution. M4 owns one Compose application window, chat, permissions, GitHub and Android intents.

Prior verified source:
- `Musabgpt/NewAl-Cloud`, `app/src/main/java/com/musab/newalcloud/WebBridge.java` — NewAl Code's authenticated localhost HTTP session proxy on port 8791.
- `Musabgpt/NewAl`, branch `ccr-e40af0a0-eec37q`, `desktop/newal_code/server.py` — `/api/state`, `/api/sessions`, `/api/sessions/{id}/send`, `/api/sessions/{id}`.
- NewAl's Android `Termux.java` — `com.termux.RUN_COMMAND`, requires user permissions and `allow-external-apps=true`.
- Termux app official RUN_COMMAND Intent documentation: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent

## New M4 data flow
1. User sends message in M4's Compose chat.
2. Direct Android intents (e.g. YouTube search) are recognized and sent by `YouTubeActions`, without any model gateway.
3. For agent text, `NewAlTermuxBridge` invokes a bounded Python worker **inside Termux** via `TermuxClient.RUN_COMMAND` (no Android-native shell/PTY).
4. Python reads the pre-existing NewAl Code pairing key within Termux's private home. It authenticates with `http://127.0.0.1:8791`, starts/reuses a NewAl session, submits the message, polls its turn events and returns a JSON receipt.
5. M4's `TermuxResultReceiver` accepts a PendingIntent result, validates the nonce, and stores a result for the initiating conversation. Only verified stdout/exit code is considered success.
6. M4 chat renders only user/assistant messages and file artifacts; internal events remain diagnostic data, not chat bubbles.
7. GitHub has a first-class entry point, with existing OAuth/Keystore support and NewAl-style token verification code, but real account flow must be accepted on a device.

## Preconditions / user action
- A compatible version of **Termux** installed from its trusted project distribution.
- User grants `com.termux.permission.RUN_COMMAND` to M4 in Android's app permissions.
- Termux `~/.termux/termux.properties` has `allow-external-apps = true`.
- The user's previously installed NewAl Code server in Termux is available on port 8791, or `newal-termux start` works.
- Pairing key lives in Termux (`.newal-cloud/key` or `.newal-code/termux/key`); M4 never reads the key into its own storage.
- GitHub connection needs separately authorized credentials. Connected GitHub in ChatGPT does NOT implicitly authorize Android.

## Current evidence and limits
- Native Arabic terminal Activity/Service are **removed from the manifest**; phone accessibility service remains, without overlay.
- Termux's documented result `err = -1` is success, not failure; regression-tested using a synthetic Android callback, not a live Termux device.
- In emulator without Termux, **no real Termux command execution can pass**; absence should yield explicit unavailable, not fallback to Alpine or a mock.
- Existing embedded Alpine code/assets may remain in source/CI until packaging cleanup is complete. This is a migration in progress.
- No claim of real YouTube playback or GitHub OAuth until verified on a user device.
- No `main` or signed production release changes until actual Termux physical-device, permission, GitHub and phone-flow acceptance.

## Acceptance (real device required)
- M4 installed with Termux and permission granted; run `python --version`, `git --version`, `node --version` and assert stdout/exit code from Termux.
- Send first NewAl message; assert actual NewAl session turn ends, no gateway fallback or false "succeeded".
- `افتح يوتيوب وابحث عن مسلسل باب الحارة وشغلي الفيديو`: verify correct YouTube search, video action and playback screen.
- No bubble controls, no `provider_fallback` / `turn_finished` chat cards.
- GitHub connect, verify owner/repositories, inspect branch/file, and require approval before writes.
- Scheduled approved command executes in Termux, result persists, no duplicate effect after restart.

**MUSAB MAX disposition:** These items are NOT verified on an actual Termux-equipped Android device as of this code revision; final release gate remains FAIL until proved.
