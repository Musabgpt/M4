package io.musab.arabicterminal;

/**
 * A read-only (except a disposable /tmp probe and normal apk index refresh)
 * in-guest diagnostic. Runs through the EXISTING Linux PTY and needs no root
 * escalation or extra Android permissions.
 */
public final class LinuxDiagnostics {
    private LinuxDiagnostics(){}
    public static String shellCommand(){
        return "printf '\\n=== ALPINE NETWORK DIAGNOSTICS ===\\n'; " +
            "printf '\\n[1] Identity and version\\n'; id; cat /etc/os-release 2>/dev/null | head -n 4; " +
            "printf '\\n[2] Linux DNS and repositories\\n'; cat /etc/resolv.conf; " +
            "cat /etc/apk/repositories; " +
            "printf '\\n[3] Filesystem access\\n'; " +
            "ls -ld /etc/apk /lib/apk/db /var/cache/apk /tmp 2>&1; " +
            "if touch /tmp/.arabic-term-probe-$$ 2>/dev/null; then " +
            "rm -f /tmp/.arabic-term-probe-$$; echo 'TMP_WRITE=OK'; " +
            "else echo 'TMP_WRITE=DENIED'; fi; " +
            "printf '\\n[4] DNS inside Linux\\n'; " +
            "nslookup dl-cdn.alpinelinux.org 2>&1 | head -n 12; " +
            "printf '\\n[5] HTTPS inside Linux (curl)\\n'; " +
            "curl -I -L -sS --connect-timeout 7 --max-time 12 " +
            "'https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz' " +
            "2>&1 | head -n 14; " +
            "printf '\\n[6] apk repository indexes\\n'; apk -vv update 2>&1; " +
            "printf '\\n[7] Signed APK v2 optional compatibility test\\n'; " +
            "if command -v apk-v2 >/dev/null 2>&1; then " +
            "apk-v2 --version; apk-v2 update 2>&1; " +
            "else echo 'apk-v2 is not installed'; fi; " +
            "printf '\\n=== END OF DIAGNOSTICS ===\\n'\\n";
    }
}
