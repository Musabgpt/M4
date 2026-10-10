package io.musab.arabicterminal;
import java.io.IOException;
/** JNI backend compiled into the APK. */
public final class NativePty {
    static { System.loadLibrary("arabicpty"); }
    private NativePty() {}
    public static native long start(boolean root,String home,String temp,int rows,int cols,
           String linuxRoot,String prootPath,String loaderPath) throws IOException;
    public static native int read(long handle,byte[] output);
    public static native int write(long handle,byte[] bytes,int length);
    public static native void resize(long handle,int rows,int cols);
    public static native void terminate(long handle);
    public static native void destroy(long handle);
}
