package io.musab.arabicterminal;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Secure, Android-compatible tar rootfs extraction.
 *
 * On Android app filesystems / SELinux domains, Files.createLink() can return
 * EPERM. Hard links must be copied to independent files, as in proot-distro.
 * Links are deferred until all regular files are extracted, so an archive
 * cannot write through a previously-created symlink.
 */
public final class SafeRootfsExtractor {
    private SafeRootfsExtractor(){}

    public static void extract(TarArchiveInputStream input,File directory)throws IOException {
        if(!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create rootfs staging directory");
        final Path base=directory.getCanonicalFile().toPath();
        final List<TarArchiveEntry> symbolic=new ArrayList<>();
        final List<TarArchiveEntry> hard=new ArrayList<>();
        TarArchiveEntry entry;
        int entries=0;
        long expanded=0;

        while((entry=input.getNextTarEntry())!=null){
            if(++entries>125000)throw new IOException("Rootfs contains too many entries");
            Path item=member(base,entry.getName());
            if(entry.isSymbolicLink()){symbolic.add(entry);continue;}
            if(entry.isLink()){hard.add(entry);continue;}
            if(entry.isDirectory()){
                Files.createDirectories(item);
                continue;
            }
            if(!entry.isFile())continue; // Ignore device nodes and FIFOs.
            long size=entry.getSize();
            if(size<0||size>180_000_000 || (expanded+=size)>950_000_000)
                throw new IOException("Rootfs extraction size limit exceeded");
            Path parent=item.getParent();
            if(parent!=null)Files.createDirectories(parent);
            if(Files.isSymbolicLink(item))
                throw new IOException("Archive entry points through a symbolic link: "+item);
            try(FileOutputStream output=new FileOutputStream(item.toFile())){
                byte[] buf=new byte[16384];
                while(size>0){
                    int read=input.read(buf,0,(int)Math.min(size,buf.length));
                    if(read<0)throw new IOException("Truncated rootfs tar entry");
                    output.write(buf,0,read);
                    size-=read;
                }
            }
            applyMode(item,entry.getMode());
        }

        // Hard links MUST NOT become real host hard links under Android SELinux.
        // The tar builder also uses GNU tar --hard-dereference, but this is a
        // defense-in-depth fallback for third-party or older rootfs archives.
        int unresolved=hard.size();
        while(unresolved>0){
            int progress=0;
            for(Iterator<TarArchiveEntry> iter=hard.iterator();iter.hasNext();){
                TarArchiveEntry link=iter.next();
                Path item=member(base,link.getName());
                Path target=member(base,link.getLinkName());
                if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))continue;
                if(item.getParent()!=null)Files.createDirectories(item.getParent());
                if(Files.exists(item,LinkOption.NOFOLLOW_LINKS))
                    Files.delete(item);
                Files.copy(target,item,StandardCopyOption.REPLACE_EXISTING,LinkOption.NOFOLLOW_LINKS);
                applyMode(item,link.getMode());
                iter.remove();progress++;
            }
            if(progress==0)throw new IOException("Unresolvable hard link in rootfs: "+hard.get(0).getName());
            unresolved=hard.size();
        }
        for(TarArchiveEntry link:symbolic){
            Path item=member(base,link.getName());
            String raw=link.getLinkName();
            if(raw==null||raw.isEmpty()||raw.indexOf('\0')>=0)
                throw new IOException("Invalid symbolic link target");
            Path destination=Paths.get(raw);
            if(!destination.isAbsolute()){
                Path checked=item.getParent().resolve(destination).normalize();
                if(!checked.startsWith(base))
                    throw new IOException("Symlink points outside rootfs: "+link.getName());
            }
            // Guest-absolute links are safe at this stage: they are only
            // recorded, never followed by this extractor or any install step.
            if(item.getParent()!=null)Files.createDirectories(item.getParent());
            if(Files.exists(item,LinkOption.NOFOLLOW_LINKS))Files.delete(item);
            Files.createSymbolicLink(item,destination);
        }
    }
    private static Path member(Path base,String raw)throws IOException{
        if(raw==null||raw.isEmpty()||raw.indexOf('\0')>=0)
            throw new IOException("Invalid archive member");
        Path name;
        try{name=Paths.get(raw.replace('\\','/')).normalize();}
        catch(RuntimeException error){throw new IOException("Invalid archive path",error);}
        if(name.isAbsolute()||name.startsWith(".."))
            throw new IOException("Absolute or traversal archive path: "+raw);
        Path resolved=base.resolve(name).normalize();
        if(!resolved.startsWith(base))
            throw new IOException("Rootfs extraction would escape target: "+raw);
        return resolved;
    }
    private static void applyMode(Path file,int mode){
        File target=file.toFile();
        if((mode&0111)!=0)target.setExecutable(true,false);
        if((mode&0004)!=0)target.setReadable(true,false);
        if((mode&0002)!=0)target.setWritable(true,false);
    }
}
