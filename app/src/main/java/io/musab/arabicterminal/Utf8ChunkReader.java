package io.musab.arabicterminal;

/** Largest complete UTF-8 prefix of a bounded PTY read buffer. */
public final class Utf8ChunkReader {
    private Utf8ChunkReader() {}

    public static int completePrefix(byte[] bytes,int count) {
        if(bytes==null || count<0 || count>bytes.length)
            throw new IllegalArgumentException("Invalid UTF-8 buffer length");
        if(count==0) return 0;
        int start=count-1;
        while(start>0 && (bytes[start]&0xc0)==0x80) start--;
        int lead=bytes[start]&0xff;
        if(start==0 && lead>=0x80 && lead<=0xbf) return count;
        int expected=lead>=0xc2 && lead<=0xdf ? 2 :
                     lead>=0xe0 && lead<=0xef ? 3 :
                     lead>=0xf0 && lead<=0xf4 ? 4 : 1;
        return count-start>=expected ? count : start;
    }
}
