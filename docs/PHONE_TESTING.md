# Device acceptance

No emulator, physical phone, authenticated ADB or active Accessibility service
was attached to this development session. Existing PhoneController and
UiControlService remain in the legacy manual terminal. Model Phone actions and
phone test tools remain unregistered. CI compilation/APK creation does not
establish real screen interaction, SELinux/ptrace compatibility or battery UX.

Use the separate development package on an ARM64 device for acceptance:

1. Open chat, then the embedded terminal; run Python version, REPL and an ANSI
   application, switch sessions and rotate the Activity without losing PTY state.
2. Run a coding request creating `md/html/txt/pdf/zip/py/sh/json`; inspect actual
   bytes, render Arabic PDF, preview HTML, save through SAF, reopen chat and
   verify persistent cards and export hashes.
3. Approve once/hourly/daily commands, inspect real run IDs/output, pause/cancel,
   reboot and check idempotency. Document Doze delays and DST window behavior.
4. Test local browser DOM and rejected file, credential, cleartext and TLS URLs.
5. Activate Accessibility only if testing existing manual phone controls; verify
   refusal and emergency stop. Secure windows remain protected.

Record device model, Android version, package/signer, permission state, actual
screenshots/logs and each supplied E2E case in V2_VALIDATION.md.
