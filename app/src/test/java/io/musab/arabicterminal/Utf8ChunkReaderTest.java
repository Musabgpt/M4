package io.musab.arabicterminal;

import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class Utf8ChunkReaderTest {
    @Test public void neverSplitsArabicCharacters() {
        byte[] arabic="مرحبا".getBytes(StandardCharsets.UTF_8);
        assertEquals(0,Utf8ChunkReader.completePrefix(arabic,1));
        assertEquals(2,Utf8ChunkReader.completePrefix(arabic,3));
        assertEquals(4,Utf8ChunkReader.completePrefix(arabic,5));
        assertEquals(arabic.length,Utf8ChunkReader.completePrefix(arabic,arabic.length));
    }

    @Test public void neverSplitsEmoji() {
        byte[] sample="ok 😀".getBytes(StandardCharsets.UTF_8);
        assertEquals(3,Utf8ChunkReader.completePrefix(sample,5));
        assertEquals(sample.length,Utf8ChunkReader.completePrefix(sample,sample.length));
    }

    @Test public void malformedByteDoesNotHangReader() {
        assertEquals(1,Utf8ChunkReader.completePrefix(new byte[] {(byte)0xff},1));
    }
}
