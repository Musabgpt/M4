package com.musabgpt.m4;

import io.musab.arabicterminal.ProjectBootstrap;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ProjectBootstrapTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static void write(java.nio.file.Path path, String text) throws Exception { Files.write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String read(java.nio.file.Path path) throws Exception { return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8); }
    private File seed(File root) throws Exception {
        File seed = new File(root, "opt/m4-seed");
        new File(seed, ".git").mkdirs(); new File(seed, "agent").mkdirs(); new File(seed, "agent_tests").mkdirs();
        write(new File(seed, "agent/agent.py").toPath(), "# actual seed bytes");
        return seed;
    }
    @Test public void firstChatHasCheckoutBeforeAnyImportAndRepeatKeepsEdits() throws Exception {
        File root = temporary.newFolder(); seed(root);
        File project = ProjectBootstrap.ensure(root);
        assertTrue(new File(project, ".git").isDirectory());
        assertEquals("# actual seed bytes", read(new File(project,"agent/agent.py").toPath()));
        write(new File(project,"agent/agent.py").toPath(), "user changes");
        ProjectBootstrap.ensure(root);
        assertEquals("user changes", read(new File(project,"agent/agent.py").toPath()));
    }
    @Test public void artifactCreatedBeforeFirstChatIsPreserved() throws Exception {
        File root = temporary.newFolder(); seed(root);
        File receipt = new File(root,"root/projects/M4/.term-agent/artifacts/receipt.txt"); receipt.getParentFile().mkdirs(); write(receipt.toPath(),"preserve");
        File project = ProjectBootstrap.ensure(root);
        assertTrue(new File(project,".git").isDirectory()); assertEquals("preserve",read(receipt.toPath()));
    }
    @Test public void unrelatedDirectoryIsNeverOverwritten() throws Exception {
        File root = temporary.newFolder(); seed(root);
        File file = new File(root,"root/projects/M4/my-work.txt"); file.getParentFile().mkdirs(); write(file.toPath(),"keep");
        assertThrows(java.io.IOException.class, () -> ProjectBootstrap.ensure(root));
        assertEquals("keep",read(file.toPath()));
    }
    @Test public void interruptedActivationRestoresPreviousData() throws Exception {
        File root = temporary.newFolder(); seed(root);
        File file = new File(root,"root/projects/.m4-bootstrap-backup/.m4/imports/x.txt"); file.getParentFile().mkdirs(); write(file.toPath(),"keep");
        File project = ProjectBootstrap.ensure(root);
        assertEquals("keep",read(new File(project,".m4/imports/x.txt").toPath()));
    }
}
