package io.musab.arabicterminal;

import android.content.Context;
import android.os.Build;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Offline installation of an ARM64 Alpine rootfs bundled with the APK. */
public final class LinuxEnvironment {
    private LinuxEnvironment(){}
    public static File rootfs(Context c){return new File(c.getFilesDir(),"alpine-rootfs");}
    public static boolean installed(Context c){
        File root=rootfs(c);
        return new File(root,".arabicterminal-installed").isFile()
            && new File(root,"etc/alpine-release").isFile()
            && new File(root,"bin/busybox").exists();
    }
    public static boolean runtimeAvailable(Context c){
        if(!"arm64-v8a".equals(Build.SUPPORTED_ABIS[0]))return false;
        File libs=new File(c.getApplicationInfo().nativeLibraryDir);
        return new File(libs,"libproot.so").isFile() &&
            new File(libs,"libproot-loader.so").isFile();
    }
    public static void install(Context c)throws Exception {
        if(installed(c) && new File(rootfs(c),"usr/bin/python3").exists() &&
                new File(rootfs(c),"usr/bin/node").exists() &&
                new File(rootfs(c),"bin/bash").exists() &&
                new File(rootfs(c),"usr/local/bin/apk-v2").exists() &&
                new File(rootfs(c),"opt/term-agent/agent.py").isFile() &&
                new File(rootfs(c),"usr/local/bin/term-agent").isFile()){
            refreshDns(c);
            return;
        }
        if(!runtimeAvailable(c))throw new IOException("مكتبات PRoot ARM64 غير موجودة في التطبيق");
        File root=rootfs(c),stage=new File(c.getFilesDir(),"alpine-staging");
        if(stage.exists())remove(stage);
        if(!stage.mkdirs())throw new IOException("تعذر إنشاء مساحة التوزيعة");
        try{
            // The bundled APK asset cannot change between these two reads.
            // Verify before extraction while keeping archive memory bounded.
            String actual;
            try(InputStream in=c.getAssets().open("alpine-rootfs.tgz")){
                actual=ArchiveVerifier.sha256(in,150_000_000L);
            }
            byte[] expected=new byte[128];int count;
            try(InputStream in=c.getAssets().open("alpine-rootfs.sha256")){
                count=in.read(expected);
            }
            if(count<=0)throw new IOException("ملف التحقق من التوزيعة غير موجود");
            String checksum=new String(expected,0,count,java.nio.charset.StandardCharsets.US_ASCII).trim();
            if(!checksum.matches("[a-fA-F0-9]{64}") ||
                    !actual.equalsIgnoreCase(checksum))
                throw new IOException("فشل التحقق من بصمة Alpine");
            try(TarArchiveInputStream tar=new TarArchiveInputStream(
                new GZIPInputStream(c.getAssets().open("alpine-rootfs.tgz")))){
                SafeRootfsExtractor.extract(tar,stage);
            }
            File home=new File(stage,"root"),tmp=new File(stage,"tmp");
            home.mkdirs();tmp.mkdirs();
            tmp.setReadable(true,false);tmp.setWritable(true,false);tmp.setExecutable(true,false);
            File etc=new File(stage,"etc");etc.mkdirs();
            File dns=new File(etc,"resolv.conf");
            if(!dns.exists())Files.write(dns.toPath(),
                "nameserver 1.1.1.1\nnameserver 8.8.8.8\n".getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII));
            // Preserve user-created content when upgrading from v0.5. Never wipe
            // the old rootfs unless the replacement has extracted successfully.
            File backup=new File(c.getFilesDir(),"alpine-backup");
            if(backup.exists())throw new IOException(
                "نسخة احتياطية قديمة موجودة. احتفظ بها قبل متابعة الترقية");
            if(root.exists()){
                preserveUserFolder(new File(root,"root"),new File(stage,"root"));
                preserveUserFolder(new File(root,"home"),new File(stage,"home"));
                if(!root.renameTo(backup))throw new IOException("تعذر حفظ توزيعة لينكس القديمة");
            }
            if(!stage.renameTo(root)){
                if(backup.exists())backup.renameTo(root);
                throw new IOException("تعذر تفعيل ملفات لينكس");
            }
            Files.write(new File(root,".arabicterminal-installed").toPath(),
                "Alpine 3.24.2 + agent-runtime-v1\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            refreshDns(c);
            if(backup.exists())remove(backup);
        }catch(Exception error){
            remove(stage);throw error;
        }
    }
    /** Use network-specific resolvers on Android (including cellular/VPN DNS)
     * rather than assuming that public 1.1.1.1 can be reached on every carrier.
     * Does not disable certificate verification or install outside the app.
     */
    static void refreshDns(Context c) throws IOException {
        File etc=new File(rootfs(c),"etc");
        if(!etc.isDirectory())return;
        StringBuilder dns=new StringBuilder();
        try{
            android.net.ConnectivityManager manager=
                (android.net.ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);
            if(manager!=null){
                android.net.Network active=manager.getActiveNetwork();
                android.net.LinkProperties properties=
                    active==null?null:manager.getLinkProperties(active);
                if(properties!=null)for(java.net.InetAddress server:properties.getDnsServers()){
                    if(dns.length()>128)break;
                    String ip=server.getHostAddress();
                    if(ip!=null&&!ip.trim().isEmpty()&&!ip.contains("%"))
                        dns.append("nameserver ").append(ip).append('\n');
                }
            }
        }catch(SecurityException ignored){}
        if(dns.length()==0)dns.append("nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        java.nio.file.Path dest=new File(etc,"resolv.conf").toPath();
        // Delete an existing link first so updates cannot follow outside rootfs.
        if(Files.isSymbolicLink(dest))Files.delete(dest);
        Files.write(dest,dns.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private static void preserveUserFolder(File src,File dst)throws IOException {
        if(!src.exists())return;
        final Path origin=src.toPath(),target=dst.toPath();
        Files.walkFileTree(origin,new java.nio.file.SimpleFileVisitor<Path>(){
            @Override public java.nio.file.FileVisitResult preVisitDirectory(
                    Path dir,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException {
                Files.createDirectories(target.resolve(origin.relativize(dir)));
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(
                    Path file,java.nio.file.attribute.BasicFileAttributes attrs)throws IOException {
                Path destination=target.resolve(origin.relativize(file));
                Files.copy(file,destination,java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    private static void remove(File folder)throws IOException {
        if(!folder.exists())return;
        try(java.util.stream.Stream<Path> all=Files.walk(folder.toPath())){
            for(Path p:(Iterable<Path>)all.sorted(java.util.Comparator.reverseOrder())::iterator)
                Files.deleteIfExists(p);
        }
    }
}
