package io.musab.arabicterminal;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

public final class SafeRootfsExtractorTest {
    private byte[] archive()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(TarArchiveOutputStream tar=new TarArchiveOutputStream(bytes)){
            byte[] body="#!/bin/sh\necho hello\n".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry script=new TarArchiveEntry("./usr/bin/unzip");
            script.setSize(body.length);script.setMode(0755);
            tar.putArchiveEntry(script);tar.write(body);tar.closeArchiveEntry();
            TarArchiveEntry hard=new TarArchiveEntry("./usr/bin/zipinfo",TarArchiveEntry.LF_LINK);
            hard.setLinkName("./usr/bin/unzip");hard.setSize(0);hard.setMode(0755);
            tar.putArchiveEntry(hard);tar.closeArchiveEntry();
            TarArchiveEntry soft=new TarArchiveEntry("./usr/bin/unzip-tool",TarArchiveEntry.LF_SYMLINK);
            soft.setLinkName("unzip");soft.setSize(0);
            tar.putArchiveEntry(soft);tar.closeArchiveEntry();
            tar.finish();
        }
        return bytes.toByteArray();
    }
    @Test public void hardLinksBecomeIndependentExecutableCopies()throws Exception {
        Path dir=Files.createTempDirectory("android-proot-hardlink-");
        try(TarArchiveInputStream input=new TarArchiveInputStream(
            new ByteArrayInputStream(archive()))){
            SafeRootfsExtractor.extract(input,dir.toFile());
            Path original=dir.resolve("usr/bin/unzip"),hard=dir.resolve("usr/bin/zipinfo");
            assertTrue(Files.exists(original));assertTrue(Files.exists(hard));
            assertTrue(Files.isExecutable(hard));
            assertFalse(Files.isSameFile(original,hard));
            assertArrayEquals(Files.readAllBytes(original),Files.readAllBytes(hard));
            assertTrue(Files.isSymbolicLink(dir.resolve("usr/bin/unzip-tool")));
        }
    }
    @Test public void termuxMigrationDoesNotBundleAlpineRootfs() {
        File obsolete=new File("src/main/assets/alpine-rootfs.tgz");
        assertFalse("Termux-only APK must never carry Alpine rootfs", obsolete.exists());
        assertFalse("M4 must not package native PRoot",
            new File("src/main/jniLibs/arm64-v8a/libproot.so").exists());
        assertFalse("M4 must not package native PTY",
            new File("src/main/jniLibs/arm64-v8a/libarabicpty.so").exists());
    }
}
