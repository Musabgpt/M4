package io.musab.arabicterminal;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/** Creates the offline checkout before native imports or the agent touch it. */
public final class ProjectBootstrap {
    private ProjectBootstrap() {}
    public static synchronized File ensure(File rootfs) throws IOException {
        File parent = new File(rootfs, "root/projects");
        File project = new File(parent, "M4");
        File previous = new File(parent, ".m4-bootstrap-backup");
        if (!project.exists() && previous.exists() && !previous.renameTo(project))
            throw new IOException("تعذر استعادة المشروع أثناء التجهيز");
        if (valid(project)) return project;
        if (project.exists()) {
            File[] files = project.listFiles();
            if (files == null) throw new IOException("تعذرت قراءة مجلد المشروع");
            for (File file : files) if (!file.getName().equals(".term-agent") && !file.getName().equals(".m4"))
                throw new IOException("مجلد المشروع يحتوي ملفات غير مهيأة؛ حُفظت دون تغيير: " + file.getName());
        }
        File seed = new File(rootfs, "opt/m4-seed");
        if (!valid(seed)) throw new IOException("نسخة مصادر M4 المضمّنة ناقصة؛ تعذر تجهيز المشروع");
        Files.createDirectories(parent.toPath());
        Path stage = Files.createTempDirectory(parent.toPath(), ".m4-bootstrap-");
        try {
            copy(seed.toPath(), stage);
            if (project.exists()) {
                for (File file : project.listFiles()) copy(file.toPath(), stage.resolve(file.getName()));
                if (previous.exists() || !project.renameTo(previous)) throw new IOException("تعذر حفظ بيانات المشروع السابقة");
            }
            if (!stage.toFile().renameTo(project)) {
                if (previous.exists()) previous.renameTo(project);
                throw new IOException("تعذر تفعيل المشروع المجهز");
            }
            remove(previous.toPath());
            return project;
        } finally { remove(stage); }
    }
    private static boolean valid(File file) {
        return new File(file, ".git").isDirectory() && new File(file, "agent/agent.py").isFile()
            && new File(file, "agent_tests").isDirectory();
    }
    private static void copy(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir))); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("رابط غير مدعوم في مصادر المشروع");
                Path destination = target.resolve(source.relativize(file));
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                if (file.toFile().canExecute()) destination.toFile().setExecutable(true, true);
                return FileVisitResult.CONTINUE;
            }
        });
    }
    private static void remove(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.delete(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE; }
        });
    }
}
