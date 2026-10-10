package io.musab.arabicterminal;

/** Pure shell protocol, shared by Android execution and JVM regression tests. */
public final class TerminalCommandProtocol {
    private TerminalCommandProtocol() {}

    /** Preserve working directory and publish a receipt even for explicit exit N. */
    public static String wrap(String command, String cwdReceipt, String exitReceipt,
                              String initialDirectory) {
        if(command==null || command.isEmpty() || cwdReceipt==null || exitReceipt==null ||
           initialDirectory==null || command.indexOf('\0')>=0)
            throw new IllegalArgumentException("Invalid terminal task protocol");
        String cwd=quote(cwdReceipt);
        String initial=quote(initialDirectory);
        String finalizeTask="_m4_exit=$?; pwd -P > "+cwd+
            "; printf '%s' \"$_m4_exit\" > "+quote(exitReceipt);
        return "if [ -s "+cwd+" ]; then " +
            "_m4_cwd=$(cat "+cwd+"); " +
            "cd \"$_m4_cwd\" 2>/dev/null || cd "+initial+"; "+
            "else cd "+initial+"; fi\n"+
            "trap "+quote(finalizeTask)+" EXIT\n"+
            command+"\n";
    }

    private static String quote(String text) {
        return "'"+text.replace("'","'\\''")+"'";
    }
}
