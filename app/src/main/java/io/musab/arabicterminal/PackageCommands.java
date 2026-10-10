package io.musab.arabicterminal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Strict apk-v2 wrapper: no arbitrary shell injection, no global upgrade. */
public final class PackageCommands {
    public enum Action { UPDATE, SEARCH, INSTALLED, DETAILS, INSTALL, REMOVE }
    private static final Set<String> PROTECTED=new HashSet<>(Arrays.asList(
        "apk-tools","apk-tools-static","alpine-base","alpine-baselayout",
        "alpine-keys","busybox","musl","musl-utils","libc-utils","proot","apk-v2"));
    private PackageCommands(){}

    public static String checkedName(String raw){
        if(raw==null)throw new IllegalArgumentException("أدخل اسم حزمة");
        String value=raw.trim().toLowerCase(Locale.ROOT);
        if(!value.matches("[a-z0-9][a-z0-9+_.-]{0,79}"))
            throw new IllegalArgumentException("اسم الحزمة يجب أن يكون إنجليزيًا دون مسافات أو رموز خاصة");
        return value;
    }
    public static String command(Action action,String rawName){
        if(action==null)throw new IllegalArgumentException("عملية غير معروفة");
        String name=(action==Action.UPDATE||action==Action.INSTALLED)?null:checkedName(rawName);
        if((action==Action.INSTALL||action==Action.REMOVE)&&PROTECTED.contains(name))
            throw new IllegalArgumentException("حزمة نظام أساسية محمية: "+name);
        String operation;
        switch(action){
            case UPDATE:operation="apk-v2 update";break;
            case INSTALLED:operation="apk-v2 info";break;
            case SEARCH:operation="apk-v2 search -v '*"+name+"*'";break;
            case DETAILS:operation="apk-v2 info -a '"+name+"'";break;
            case INSTALL:
            case REMOVE:
                String verb=action==Action.INSTALL?"add":"del";
                String quoted="'"+name+"'";
                String backup="mkdir -p /root/.arabicterminal-apk-backups && "+
                    "backup=$(mktemp -d /root/.arabicterminal-apk-backups/$(date +%Y%m%d-%H%M%S)-XXXXXX) && "+
                    "cp -a /lib/apk/db \"$backup/db\" && "+
                    "cp -a /etc/apk/world \"$backup/world\"";
                operation="apk-v2 "+verb+" --simulate "+quoted+
                    " && ( "+backup+" ) && apk-v2 "+verb+" "+quoted;
                break;
            default:throw new IllegalArgumentException("عملية غير مدعومة");
        }
        // Atomic across all sessions sharing Alpine /root; never remove a
        // possibly live lock without the owning process exiting.
        if(action==Action.UPDATE||action==Action.INSTALL||action==Action.REMOVE) {
            String lock="/root/.arabicterminal-apk-manager.lock";
            operation="( umask 077; "+
                "if mkdir '"+lock+"' 2>/dev/null; then "+
                "trap 'rmdir "+lock+" 2>/dev/null || true' EXIT; "+
                "trap 'exit 129' HUP; trap 'exit 130' INT; trap 'exit 143' TERM; "+
                operation+"; "+
                "else echo 'Package manager busy or stale lock: "+lock+"' >&2; false; fi )";
        }
        return "printf '\\n=== ALPINE PACKAGE MANAGER (apk-v2) ===\\n'; "+
            "if command -v apk-v2 >/dev/null 2>&1; then ( "+operation+" ); "+
            "else echo 'apk-v2 missing from this Alpine build'; false; fi; "+
            "pkg_rc=$?; printf '\\nPACKAGE_EXIT_CODE=%s\\n' \"$pkg_rc\"\n";
    }
}
