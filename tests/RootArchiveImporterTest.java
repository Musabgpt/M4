import io.musab.arabicterminal.RootArchive;
import io.musab.arabicterminal.RootArchiveImporter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RootArchiveImporterTest {
    private static int count;
    private static void check(boolean ok, String label) {
        count++;
        if (!ok) throw new AssertionError(label);
    }
    private static byte[] zip(String path, String contents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream writer = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            writer.putNextEntry(new ZipEntry(path));
            writer.write(contents.getBytes(StandardCharsets.UTF_8));
            writer.closeEntry();
        }
        return out.toByteArray();
    }
    private static void reject(byte[] archive, Path home) throws Exception {
        try {
            RootArchiveImporter.restore(new ByteArrayInputStream(archive), home);
            throw new AssertionError("Unsafe ZIP was accepted");
        } catch(IOException expected) { count++; }
        try (Stream<Path> files=Files.list(home)) {
            check(files.noneMatch(x->x.getFileName().toString().startsWith(".arabic-restore-")),
                    "staging cleaned after failed restore");
        }
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("restore-security-test");
        try {
            Path original=Files.createDirectories(temp.resolve("original"));
            Files.write(original.resolve("كود.py"), "print(7)".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(original.resolve("empty-folder"));
            ByteArrayOutputStream archive=new ByteArrayOutputStream();
            RootArchive.export(original,archive);
            Path home=Files.createDirectory(temp.resolve("home"));
            Files.write(home.resolve("كود.py"), "original stays".getBytes(StandardCharsets.UTF_8));
            RootArchiveImporter.Result first=RootArchiveImporter.restore(
                    new ByteArrayInputStream(archive.toByteArray()),home);
            check(first.files==1,"restored file count");
            check(first.directories==1,"restored directory count");
            check(first.destination.startsWith(home),"restore beneath home");
            check(Files.exists(first.destination.resolve("empty-folder")),"empty dir restored");
            check(new String(Files.readAllBytes(first.destination.resolve("كود.py")),
                    StandardCharsets.UTF_8).equals("print(7)"),"Unicode path and contents restored");
            check(new String(Files.readAllBytes(home.resolve("كود.py")),
                    StandardCharsets.UTF_8).equals("original stays"),"existing project preserved");
            RootArchiveImporter.Result again=RootArchiveImporter.restore(
                    new ByteArrayInputStream(archive.toByteArray()),home);
            check(!first.destination.equals(again.destination),"repeated restore unique folder");

            reject(zip("../escaped.txt","evil"),home);
            reject(zip("/absolute.txt","evil"),home);
            reject(zip("dir/../../escaped.txt","evil"),home);
            reject(zip("dir\\evil.txt","evil"),home);
            reject(zip("C:/evil.txt","evil"),home);
            reject(new byte[]{1,2,3,4},home);
            check(!Files.exists(temp.resolve("escaped.txt")),"no parent traversal");

            ByteArrayOutputStream duplicate=new ByteArrayOutputStream();
            try (ZipOutputStream writer=new ZipOutputStream(duplicate)) {
                writer.putNextEntry(new ZipEntry("a/./b"));
                writer.write(1);
                writer.closeEntry();
            }
            reject(duplicate.toByteArray(),home);

            ByteArrayOutputStream inconsistent=new ByteArrayOutputStream();
            try (ZipOutputStream writer=new ZipOutputStream(inconsistent)) {
                writer.putNextEntry(new ZipEntry("nested/file"));
                writer.write(1);
                writer.closeEntry();
                writer.putNextEntry(new ZipEntry("nested"));
                writer.write(2);
                writer.closeEntry();
            }
            reject(inconsistent.toByteArray(),home);
            System.out.println("PASS: "+count+" secure Alpine recovery tests");
        } finally {
            try (Stream<Path> files=Files.walk(temp)) {
                for(Path path:(Iterable<Path>)files
                        .sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
