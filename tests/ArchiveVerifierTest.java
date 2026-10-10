import io.musab.arabicterminal.ArchiveVerifier;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ArchiveVerifierTest {
    private static int count;
    private static void check(boolean condition, String message) {
        count++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        byte[] input="abc".getBytes(StandardCharsets.US_ASCII);
        String expected="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        check(ArchiveVerifier.sha256(new ByteArrayInputStream(input),100).equals(expected),
            "canonical SHA-256 checksum");
        check(ArchiveVerifier.sha256(new ByteArrayInputStream(input),3).equals(expected),
            "inclusive maximum byte size");
        try {
            ArchiveVerifier.sha256(new ByteArrayInputStream(input),2);
            throw new AssertionError("oversized archive must fail");
        } catch (IOException correct) { count++; }
        try {
            ArchiveVerifier.sha256(new ByteArrayInputStream(new byte[0]),100);
            throw new AssertionError("empty archive must fail");
        } catch (IOException correct) { count++; }
        try {
            ArchiveVerifier.sha256(null,100);
            throw new AssertionError("null archive must fail");
        } catch (IllegalArgumentException correct) { count++; }
        try {
            ArchiveVerifier.sha256(new ByteArrayInputStream(input),0);
            throw new AssertionError("invalid limit must fail");
        } catch (IllegalArgumentException correct) { count++; }
        System.out.println("PASS: "+count+" incremental Alpine checksum tests");
    }
}
