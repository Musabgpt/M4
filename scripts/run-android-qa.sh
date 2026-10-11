#!/usr/bin/env bash
set -euo pipefail
collect() {
    mkdir -p qa-device
    adb logcat -d -v threadtime > qa-device/logcat.txt || true
    # AGP may uninstall the debug APK before this EXIT trap; do not publish a false tar.
    if adb exec-out run-as dev.newal.code.lite.connectors.m4dev tar -C files -cf - qa > qa-device/receipts.tmp 2>qa-device/receipts-error.txt && tar -tf qa-device/receipts.tmp >/dev/null 2>&1; then
        mv qa-device/receipts.tmp qa-device/receipts.tar
    else
        rm -f qa-device/receipts.tmp
        echo "Filesystem QA receipts unavailable after instrumentation teardown; use M4QAReceipt logcat entries." > qa-device/receipts-status.txt
    fi
}
trap collect EXIT
gradle --no-daemon connectedDebugAndroidTest --stacktrace
