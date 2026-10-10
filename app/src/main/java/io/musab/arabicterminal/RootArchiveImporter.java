package io.musab.arabicterminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Restores a ZIP backup into a NEW folder under Alpine /root.
 * Never overwrites existing projects, creates symlinks, or trusts ZIP paths.
 * Extraction is staged privately; only complete verified ZIPs are published.
 */
public final class RootArchiveImporter {
    public static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;
    public static final long MAX_FILE_BYTES = 1024L * 1024 * 1024;
    public static final int MAX_ENTRIES = 20000;
    private RootArchiveImporter() {}

    public static final class Result {
        public final Path destination;
        public final int files, directories;
        public final long bytes;
        Result(Path destination, int files, int directories, long bytes) {
            this.destination = destination;
            this.files = files;
            this.directories = directories;
            this.bytes = bytes;
        }
    }

    private static Path checkedRelative(String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\\') >= 0 ||
                name.indexOf('\0') >= 0 || name.startsWith("/"))
            throw new IOException("Unsafe ZIP entry path");
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            if (part.equals(".") || part.equals("..") || part.indexOf(':') >= 0)
                throw new IOException("Unsafe ZIP entry path");
        }
        Path relative = Paths.get(name).normalize();
        if (relative.isAbsolute() || relative.toString().isEmpty())
            throw new IOException("Unsafe ZIP entry path");
        return relative;
    }

    public static Result restore(InputStream source, Path home) throws IOException {
        if (source == null || home == null ||
                !Files.isDirectory(home, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Alpine /root is not available");
        Path staging = Files.createTempDirectory(home, ".arabic-restore-");
        boolean published = false;
        try {
            int files = 0, dirs = 0, entries = 0;
            long bytes = 0;
            Set<Path> seen = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(source, StandardCharsets.UTF_8)) {
                ZipEntry entry;
                byte[] buffer = new byte[16384];
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > MAX_ENTRIES)
                        throw new IOException("Too many files in backup");
                    Path relative = checkedRelative(entry.getName());
                    if (!seen.add(relative))
                        throw new IOException("Duplicate ZIP path");
                    Path target = staging.resolve(relative).normalize();
                    if (!target.startsWith(staging))
                        throw new IOException("ZIP entry escapes restore directory");
                    if (entry.isDirectory()) {
                        Files.createDirectories(target);
                        dirs++;
                    } else {
                        Files.createDirectories(target.getParent());
                        long fileBytes = 0;
                        try (OutputStream out = Files.newOutputStream(target,
                                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS)) {
                            int count;
                            while ((count = zip.read(buffer)) != -1) {
                                if (count > MAX_FILE_BYTES - fileBytes ||
                                        count > MAX_TOTAL_BYTES - bytes)
                                    throw new IOException("Backup exceeds safe restore limits");
                                out.write(buffer, 0, count);
                                fileBytes += count;
                                bytes += count;
                            }
                        }
                        files++;
                    }
                    zip.closeEntry(); // Performs CRC verification where available.
                }
            }
            if (entries == 0)
                throw new IOException("Empty or invalid ZIP backup");
            // Keep restore separate from original /root files. A unique name avoids
            // overwriting previous restores when several backups are imported.
            String prefix = "Recovered-" + new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
            for (int attempt = 0; attempt < 1000; attempt++) {
                Path target = home.resolve(prefix + "-" + attempt);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                try {
                    try {
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(staging, target);
                    }
                    published = true;
                    return new Result(target, files, dirs, bytes);
                } catch (java.nio.file.FileAlreadyExistsException busy) {
                    // Concurrent restore chose the same destination. Try another.
                }
            }
            throw new IOException("Unable to allocate a recovery directory");
        } finally {
            if (!published && Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.walk(staging)) {
                    for (Path item : (Iterable<Path>) paths
                            .sorted(java.util.Comparator.reverseOrder())::iterator)
                        Files.deleteIfExists(item);
                }
            }
        }
    }
}
