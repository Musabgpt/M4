#!/usr/bin/env bash
set -euo pipefail
collect() {
    mkdir -p qa-device
    adb logcat -d -v threadtime > qa-device/logcat.txt || true
    adb pull /data/local/tmp/m4-workbench-first-open.png qa-device/workbench-first-open.png || true
    adb pull /data/local/tmp/m4-agent-conversation.png qa-device/agent-conversation.png || true
    adb exec-out run-as dev.newal.code.lite.connectors.m4dev tar -C files -cf - qa > qa-device/receipts.tar || true
}
trap collect EXIT
if [ "${M4_TEST_TERMUX_ENGINE:-false}" = "true" ]; then
    bash scripts/prepare-termux-qa.sh
    gradle --no-daemon connectedDebugAndroidTest --stacktrace \
      '-Pandroid.testInstrumentationRunnerArguments.class=com.musabgpt.m4.TermuxEngineInstrumentedTest' \
      '-Pandroid.testInstrumentationRunnerArguments.termuxEngine=true'
    exit 0
fi
# Run the initial-screen journey first on this fresh emulator, before any chat
# test can prepare the workspace and hide a first-install regression.
gradle --no-daemon connectedDebugAndroidTest --stacktrace \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.musabgpt.m4.WorkbenchInstrumentedTest#initialExplorerBootstrapsWithoutSendingChatAndNavigationIsVisible'
gradle --no-daemon connectedDebugAndroidTest --stacktrace
