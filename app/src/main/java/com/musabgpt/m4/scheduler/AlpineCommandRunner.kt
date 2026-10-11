package com.musabgpt.m4.scheduler

import android.content.Context
import io.musab.arabicterminal.LinuxEnvironment
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class CommandResult(val status: String, val exitCode: Int?, val stdout: String, val stderr: String, val truncated: Boolean = false)

/** Bounded batch execution in the same private rootfs. This is not an interactive PTY. */
object AlpineCommandRunner {
    fun run(context: Context, command: String, timeoutSeconds: Long = 120L, stopped: () -> Boolean): CommandResult {
        if (!LinuxEnvironment.installed(context) || !LinuxEnvironment.runtimeAvailable(context))
            return CommandResult("blocked", null, "", "Alpine المدمجة غير جاهزة؛ افتح الطرفية مرة واحدة")
        val libraries = context.applicationInfo.nativeLibraryDir
        val builder = ProcessBuilder(libraries + "/libproot.so", "-r", LinuxEnvironment.rootfs(context).absolutePath,
            "-0", "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root/projects/M4", "/usr/bin/python3", "-c", LOCKED_COMMAND, command)
        builder.environment().apply {
            keys.filter { it.endsWith("_TOKEN") || it.endsWith("_KEY") || it.endsWith("_SECRET") || it.endsWith("_PASSWORD") }.forEach { remove(it) }
            put("PROOT_LOADER", libraries + "/libproot-loader.so")
            // Match native PTY PRoot: disable seccomp only where required.
            // On Android x86_64 emulator PROOT_NO_SECCOMP=1 can break fork.
            if (android.os.Build.SUPPORTED_ABIS.firstOrNull() == "x86_64") remove("PROOT_NO_SECCOMP")
            else put("PROOT_NO_SECCOMP", "1")
            put("PROOT_TMP_DIR", context.cacheDir.absolutePath); put("LD_LIBRARY_PATH", libraries)
            put("HOME", "/root"); put("LANG", "C.UTF-8")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        }
        val process = try { builder.start() } catch (e: Exception) { return CommandResult("failed", null, "", e.message ?: "تعذر تشغيل PRoot") }
        process.outputStream.close()
        val stdout = StringBuilder(); val stderr = StringBuilder()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        fun reader(input: InputStream, output: StringBuilder) = Thread {
            try { input.reader(Charsets.UTF_8).use { stream ->
                val chars = CharArray(2048)
                while (true) { val count = stream.read(chars); if (count < 0) break
                    synchronized(output) { val remaining = (64000 - output.length).coerceAtLeast(0)
                        output.append(chars, 0, count.coerceAtMost(remaining)); if (count > remaining) truncated.set(true) }
                }
            } } catch (_: Exception) { }
        }.apply { isDaemon = true; start() }
        val outReader = reader(process.inputStream, stdout); val errReader = reader(process.errorStream, stderr)
        // Interactive coding runs may be long; lightweight diagnostics can
        // set a shorter deadline without changing scheduled-task defaults.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.coerceIn(1L, 120L))
        var status: String? = null
        while (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
            if (stopped()) { status = "canceled"; break }
            if (System.nanoTime() >= deadline) { status = "timed_out"; break }
        }
        if (status != null) { process.destroy(); if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly() }
        val ended = process.waitFor(2, TimeUnit.SECONDS)
        outReader.join(1000); errReader.join(1000)
        val exit = if (ended) process.exitValue() else null
        return CommandResult(status ?: if (exit == 0) "succeeded" else "failed", exit,
            synchronized(stdout) { stdout.toString() }, synchronized(stderr) { stderr.toString() }, truncated.get())
    }
    private val LOCKED_COMMAND = """
        import fcntl, pathlib, subprocess, sys
        folder = pathlib.Path('/root/projects/M4/.term-agent')
        folder.mkdir(exist_ok=True)
        with (folder/'active.lock').open('a') as lock:
            try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                print('Project busy: another chat or scheduled task holds the lock', file=sys.stderr)
                sys.exit(75)
            sys.exit(subprocess.call(['/bin/bash', '-lc', sys.argv[1]]))
    """.trimIndent()
}
