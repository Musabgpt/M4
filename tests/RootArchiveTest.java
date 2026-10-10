import io.musab.arabicterminal.RootArchive;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RootArchiveTest {
    private static int count;
    private static void check(boolean ok, String message) {
        count++;
        if (!ok) throw new AssertionError(message);
    }

    private static Map<String,String> unzip(byte[] bytes) throws IOException {
        Map<String,String> result = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(
                new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[512];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                result.put(entry.getName(), out.toString("UTF-8"));
            }
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("root-archive-test");
        try {
            Path home = Files.createDirectory(root.resolve("home"));
            Files.write(home.resolve("main.py"), "print('ok')".getBytes(StandardCharsets.UTF_8));
            Files.createDirectories(home.resolve("nested/empty"));
            Files.write(home.resolve("nested/مرحبا.txt"), "عربي".getBytes(StandardCharsets.UTF_8));
            Files.createDirectory(home.resolve("only-empty"));
            Path external = root.resolve("not-in-home.txt");
            Files.write(external, "private outside content".getBytes(StandardCharsets.UTF_8));
            boolean linkCreated = false;
            try {
                Files.createSymbolicLink(home.resolve("external-link"), external);
                linkCreated = true;
            } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
                // Filesystems without symlinks still exercise normal backup.
            }

            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            RootArchive.Summary summary = RootArchive.export(home, archive);
            Map<String,String> extracted = unzip(archive.toByteArray());
            check(summary.files == 2, "two regular files only");
            check(summary.directories == 3, "empty and nested directories included");
            check(summary.bytes > 0, "byte count");
            check(extracted.get("main.py").equals("print('ok')"), "source contents");
            check(extracted.get("nested/مرحبا.txt").equals("عربي"), "UTF-8 file names and contents");
            check(extracted.containsKey("nested/empty/"), "nested empty directory");
            check(extracted.containsKey("only-empty/"), "empty directory");
            check(!extracted.containsKey("external-link"), "link target never archived");
            check(!extracted.toString().contains("private outside content"), "no outside leak");
            check(summary.skipped == (linkCreated ? 1 : 0), "skipped entry count");
            check(extracted.keySet().stream().noneMatch(s -> s.startsWith("/") || s.contains("../")),
                    "only relative archive paths");

            try {
                RootArchive.export(root.resolve("missing"), new ByteArrayOutputStream());
                throw new AssertionError("Expected missing home to be rejected");
            } catch (IOException expected) { count++; }
            System.out.println("PASS: " + count + " root backup tests");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}
