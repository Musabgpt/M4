#!/usr/bin/env bash
set -euo pipefail
# Official debug build for an isolated emulator, never a production signing key.
TERMUX_QA_APK="$RUNNER_TEMP/m4-termux-qa.apk"
curl --fail --location --retry 3 'https://github.com/termux/termux-app/releases/download/v0.118.3/termux-app_v0.118.3%2Bgithub-debug_x86_64.apk' -o "$TERMUX_QA_APK"
echo "3550e61f4d9eb49b712fd1bd9519dc37085a4d8eb597c57a340f0a64859b7144  $TERMUX_QA_APK" | sha256sum --check
adb install -r "$TERMUX_QA_APK"
adb shell am start -n com.termux/.app.TermuxActivity
for attempt in $(seq 1 120); do
  if adb shell run-as com.termux test -x files/usr/bin/bash; then break; fi
  sleep 1
done
adb shell run-as com.termux test -x files/usr/bin/bash
adb shell run-as com.termux mkdir -p files/home/.termux
printf 'allow-external-apps=true\n' | adb shell run-as com.termux tee files/home/.termux/termux.properties
adb shell am force-stop com.termux
adb shell am start -n com.termux/.app.TermuxActivity
