import io.musab.arabicterminal.SafeFileImporter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class SafeFileImporterTest {
    private static int count;
    private static void check(boolean ok, String message) {
        count++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("safe-import-test");
        try {
            Path folder=temp.resolve("Alpine/imports");
            byte[] content="ملف مشروع".getBytes(StandardCharsets.UTF_8);
            Path first=SafeFileImporter.copy(new ByteArrayInputStream(content),
                    folder, "../\u0645\u0634\u0631\u0648\u0639.txt");
            check(first.getParent().equals(folder), "import stays inside folder");
            check(!first.getFileName().toString().contains(".."), "path traversal sanitized");
            check(new String(Files.readAllBytes(first),StandardCharsets.UTF_8).equals("ملف مشروع"),
                    "UTF-8 file contents");
            Path second=SafeFileImporter.copy(new ByteArrayInputStream("second".getBytes(StandardCharsets.UTF_8)),
                    folder, "../\u0645\u0634\u0631\u0648\u0639.txt");
            check(!second.equals(first), "name collision allocates a new file");
            check(new String(Files.readAllBytes(first),StandardCharsets.UTF_8).equals("ملف مشروع"),
                    "existing data is not overwritten");
            check(SafeFileImporter.safeName("   ").equals("imported"), "fallback name");
            check(SafeFileImporter.safeName("x/y\\z").equals("x_y_z"), "separators removed");

            try {
                SafeFileImporter.copy(new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("broken provider"); }
                }, folder, "partial");
                throw new AssertionError("provider failure must abort import");
            } catch (IOException correct) { count++; }
            try (Stream<Path> paths=Files.list(folder)) {
                check(paths.noneMatch(p->p.getFileName().toString().startsWith(".arabic-import-")),
                        "failed import cleans temporary files");
            }
            check(Files.notExists(folder.resolve("partial")), "failed file never published");
            Path symlink=temp.resolve("link");
            try {
                Files.createSymbolicLink(symlink,folder);
                try {
                    SafeFileImporter.copy(new ByteArrayInputStream(content),symlink,"test");
                    throw new AssertionError("symlink directory should be blocked");
                } catch (IOException correct) { count++; }
            } catch (UnsupportedOperationException | IOException | SecurityException ignored) {}
            System.out.println("PASS: "+count+" safe file import tests");
        } finally {
            try (Stream<Path> paths=Files.walk(temp)) {
                for(Path p:(Iterable<Path>)paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(p);
            }
        }
    }
}
