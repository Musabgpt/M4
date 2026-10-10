import io.musab.arabicterminal.CommandBatch;
public final class CommandBatchTest {
    private static int passed;
    private static void match(String a,String b){
        if(!CommandBatch.toShell(a).equals(b))throw new AssertionError(a);
        passed++;
    }
    public static void main(String[] args){
        match("echo 1\necho 2","echo 1\necho 2\n");
        match("cd /tmp\r\npwd\r\n","cd /tmp\npwd\n");
        match("cat <<'EOF'\nhello\nEOF","cat <<'EOF'\nhello\nEOF\n");
        match("for i in 1 2; do echo $i; done","for i in 1 2; do echo $i; done\n");
        match("echo a\n\n echo b\n","echo a\n\n echo b\n");
        match("","");match(null,"");
        match("echo عربي","echo عربي\n");
        System.out.println("PASS: "+passed+" multi-line shell input tests");
    }
}
