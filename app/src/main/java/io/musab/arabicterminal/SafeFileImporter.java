package io.musab.arabicterminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Import a SAF stream without overwriting a previous file or exposing half-written files. */
public final class SafeFileImporter {
    private SafeFileImporter() {}

    public static String safeName(String suggested) {
        String name = suggested == null ? "" : suggested;
        name = name.replace('/', '_').replace('\\', '_').replace("..", "_")
                .replaceAll("\\p{Cntrl}", "_").trim();
        if (name.isEmpty() || name.equals(".")) name = "imported";
        if (name.length() > 120) name = name.substring(0, 120);
        return name;
    }

    public static Path copy(InputStream in, Path directory, String suggestedName)
            throws IOException {
        if (in == null || directory == null) throw new IOException("No source or destination");
        if (Files.isSymbolicLink(directory))
            throw new IOException("Import destination cannot be a symbolic link");
        Files.createDirectories(directory);
        Path temp = Files.createTempFile(directory, ".arabic-import-", ".part");
        String name = safeName(suggestedName);
        try {
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] bytes = new byte[16384];
                int n;
                while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
            }
            for (int i = 0; i < 10000; i++) {
                Path dest = directory.resolve(i == 0 ? name : i + "-" + name);
                try {
                    Files.move(temp, dest); // Deliberately no REPLACE_EXISTING.
                    return dest;
                } catch (FileAlreadyExistsException collision) {
                    // Keep all previous user files; try another name.
                }
            }
            throw new IOException("Too many files with the same name");
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
