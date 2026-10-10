package io.musab.arabicterminal;
/** Keeps complete pasted shell scripts intact, including heredocs and loops. */
public final class CommandBatch {
    private CommandBatch(){}
    public static String toShell(String value){
        if(value==null||value.isEmpty())return "";
        String text=value.replace("\r\n","\n").replace('\r','\n');
        return text.endsWith("\n")?text:text+"\n";
    }
}
