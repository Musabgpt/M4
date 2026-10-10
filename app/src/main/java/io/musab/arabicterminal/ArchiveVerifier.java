package io.musab.arabicterminal;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Bounded, incremental checksum for offline Alpine archives. */
public final class ArchiveVerifier {
    private ArchiveVerifier() {}

    public static String sha256(InputStream archive, long maxBytes) throws IOException {
        if (archive == null || maxBytes <= 0)
            throw new IllegalArgumentException("Invalid archive or size limit");
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        byte[] buffer = new byte[16384];
        long total = 0;
        int count;
        while ((count = archive.read(buffer)) != -1) {
            if (count > maxBytes - total)
                throw new IOException("Alpine archive exceeds the expected size limit");
            digest.update(buffer, 0, count);
            total += count;
        }
        if (total == 0) throw new IOException("Alpine archive is empty");
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest())
            result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }
}
