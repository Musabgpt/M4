#!/usr/bin/env bash
set -euo pipefail
collect() {
    mkdir -p qa-device
    adb logcat -d -v threadtime > qa-device/logcat.txt || true
    adb pull /data/local/tmp/m4-workbench-first-open.png qa-device/workbench-first-open.png || true
    adb exec-out run-as dev.newal.code.lite.connectors.m4dev tar -C files -cf - qa > qa-device/receipts.tar || true
}
trap collect EXIT
gradle --no-daemon connectedDebugAndroidTest --stacktrace
