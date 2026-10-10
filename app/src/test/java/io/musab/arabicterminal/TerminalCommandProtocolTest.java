package io.musab.arabicterminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class TerminalCommandProtocolTest {
    @Test public void explicitExitPublishesStatusAndWorkingDirectory() throws Exception {
        Path root=Files.createTempDirectory("m4-command-test");
        Path cwd=root.resolve("cwd");
        Path done=root.resolve("done");
        String task=TerminalCommandProtocol.wrap("exit 7",cwd.toString(),
            done.toString(),root.toString());
        Process child=new ProcessBuilder("sh","-c",task).start();
        assertEquals(7,child.waitFor());
        assertEquals("7",new String(Files.readAllBytes(done),StandardCharsets.UTF_8));
        assertEquals(root.toRealPath().toString(),
            new String(Files.readAllBytes(cwd),StandardCharsets.UTF_8).trim());
    }

    @Test public void preservesDirectoryAcrossRealShellInvocations() throws Exception {
        Path root=Files.createTempDirectory("m4-cwd-test");
        Path sub=Files.createDirectory(root.resolve("source"));
        Path cwd=root.resolve("cwd");
        Path done=root.resolve("done");
        String first=TerminalCommandProtocol.wrap("cd source",cwd.toString(),
            done.toString(),root.toString());
        Process one=new ProcessBuilder("sh","-c",first).start();
        assertEquals(0,one.waitFor());
        String second=TerminalCommandProtocol.wrap("pwd",cwd.toString(),
            done.toString(),root.toString());
        Process two=new ProcessBuilder("sh","-c",second).start();
        assertEquals(0,two.waitFor());
        assertEquals(sub.toRealPath().toString(),
            new String(Files.readAllBytes(cwd),StandardCharsets.UTF_8).trim());
    }
}
