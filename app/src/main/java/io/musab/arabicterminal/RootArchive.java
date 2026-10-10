package io.musab.arabicterminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.SimpleFileVisitor;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Stream an explicit backup of Alpine /root into a user-selected SAF document.
 * It does not follow symlinks or load large projects into memory.
 * A ZIP is a copy, not an atomic snapshot of files being modified concurrently.
 */
public final class RootArchive {
    private RootArchive() {}

    public static final class Summary {
        public int files;
        public int directories;
        public int skipped;
        public long bytes;
    }

    public static Summary export(Path home, OutputStream destination) throws IOException {
        if (home == null || destination == null ||
                !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Alpine /root is not available");

        Summary summary = new Summary();
        try (ZipOutputStream zip = new ZipOutputStream(destination,
                java.nio.charset.StandardCharsets.UTF_8)) {
            Files.walkFileTree(home, new SimpleFileVisitor<Path>() {
                @Override public FileVisitResult preVisitDirectory(Path dir,
                        BasicFileAttributes attrs) throws IOException {
                    if (!dir.equals(home)) {
                        String name = home.relativize(dir).toString()
                                .replace(java.io.File.separatorChar, '/') + "/";
                        zip.putNextEntry(new ZipEntry(name));
                        zip.closeEntry();
                        summary.directories++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file,
                        BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile()) {
                        // Never archive the target of a symlink outside /root.
                        summary.skipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    String name = home.relativize(file).toString()
                            .replace(java.io.File.separatorChar, '/');
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream in = Files.newInputStream(file,
                            LinkOption.NOFOLLOW_LINKS)) {
                        byte[] buffer = new byte[16384];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            zip.write(buffer, 0, count);
                            summary.bytes += count;
                        }
                    }
                    zip.closeEntry();
                    summary.files++;
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file,
                        IOException error) throws IOException {
                    throw error; // Never report a partial backup as successful.
                }
            });
            zip.finish();
        }
        return summary;
    }
}
