package com.musabgpt.m4.runtime

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.ProjectBootstrap
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Owns an optional, authenticated, loopback-only Code-OSS process. No Termux dependency. */
class DeveloperHost : ViewModel() {
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var endpoint by mutableStateOf<String?>(null); private set
    var password by mutableStateOf(""); private set
    private var job: Job? = null
    @Volatile private var process: Process? = null
    private val log = StringBuilder()

    fun open(context: Context) {
        if (busy || endpoint != null) return
        val app = context.applicationContext
        busy = true
        status = "تجهيز محرر Code-OSS…"
        job = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LinuxEnvironment.install(app)
                    ProjectBootstrap.ensure(LinuxEnvironment.rootfs(app))
                    val script = File(LinuxEnvironment.rootfs(app), "root/.m4/developer-host-install.sh")
                    script.parentFile!!.mkdirs()
                    app.assets.open("developer-host-install.sh").use { input -> script.outputStream().use { input.copyTo(it) } }
                    val installer = launchProcess(app, listOf("/bin/bash", "/root/.m4/developer-host-install.sh"))
                    process = installer
                    drain(installer)
                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
                    while (!installer.waitFor(200, TimeUnit.MILLISECONDS)) {
                        ensureActive()
                        check(System.nanoTime() < deadline) { "انتهت مهلة التثبيت؛ أعد المحاولة على اتصال مستقر" }
                        withContext(Dispatchers.Main) { status = tail() }
                    }
                    check(installer.exitValue() == 0) { "تعذر تثبيت محرر الإضافات:\n${tail()}" }
                    val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    val secret = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                    val server = launchProcess(app, listOf(BINARY, "--bind-addr", "127.0.0.1:$port", "--auth", "password",
                        "--disable-telemetry", "--disable-update-check", "--user-data-dir", "/root/.local/share/m4-code/user-data",
                        "--extensions-dir", "/root/.local/share/m4-code/extensions", "/root/projects/M4"), secret)
                    process = server
                    drain(server)
                    val base = "http://127.0.0.1:$port"
                    var ready = false
                    repeat(120) {
                        if (!ready) {
                            ensureActive()
                            check(server.isAlive) { "توقف محرر الإضافات:\n${tail()}" }
                            ready = loginReady(base)
                            if (!ready) delay(500)
                        }
                    }
                    check(ready) { "لم يصبح محرر الإضافات جاهزًا:\n${tail()}" }
                    withContext(Dispatchers.Main) { password = secret; endpoint = base; status = ""; busy = false }
                    // Retain ownership and surface unexpected process exit even after readiness.
                    while (server.isAlive) { delay(1000); ensureActive() }
                    error("توقف محرر الإضافات:\n${tail()}")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { status = failure.message ?: "تعذر تشغيل المحرر" }
            finally {
                withContext(NonCancellable + Dispatchers.IO) { terminate() }
                endpoint = null; password = ""; busy = false
            }
        }
    }

    fun stop() { job?.cancel(); status = "متوقف" }
    override fun onCleared() { job?.cancel(); process?.destroy(); super.onCleared() }
    private fun terminate() {
        process?.let { it.destroy(); if (!it.waitFor(2, TimeUnit.SECONDS)) it.destroyForcibly() }
        process = null
    }
    private fun tail() = synchronized(log) { log.toString().takeLast(2500) }
    private fun drain(child: Process) {
        synchronized(log) { log.setLength(0) }
        child.outputStream.close()
        Thread {
            runCatching { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                synchronized(log) { log.appendLine(line); if (log.length > 8000) log.delete(0, log.length - 6000) }
            } } }
        }.apply { isDaemon = true; start() }
    }
    private fun loginReady(base: String): Boolean = runCatching {
        val connection = URL("$base/login").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 500; connection.readTimeout = 500
            connection.instanceFollowRedirects = false
            connection.responseCode == 200 && connection.inputStream.bufferedReader().use { it.readText().contains("password") }
        } finally { connection.disconnect() }
    }.getOrDefault(false)

    private fun launchProcess(context: Context, args: List<String>, secret: String? = null): Process {
        val libs = context.applicationInfo.nativeLibraryDir
        return ProcessBuilder(listOf("$libs/libproot.so", "--kill-on-exit", "-r", LinuxEnvironment.rootfs(context).absolutePath,
            "-0", "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root/projects/M4") + args).apply {
            redirectErrorStream(true)
            environment().apply {
                clear()
                put("PROOT_LOADER", "$libs/libproot-loader.so"); put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", context.cacheDir.absolutePath); put("LD_LIBRARY_PATH", libs)
                put("HOME", "/root"); put("LANG", "C.UTF-8")
                put("SHELL", "/bin/bash")
                put("npm_config_script_shell", "/bin/bash")
                put("PATH", "/root/.local/share/m4-code/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                if (secret != null) put("PASSWORD", secret)
            }
        }.start()
    }
    companion object { const val BINARY = "/root/.local/share/m4-code/4.141.0/node_modules/.bin/code-server" }
}
