package com.musabgpt.m4

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.TerminalService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * The chat composer IS the terminal input, and the chat timeline IS its display.
 * No ModalBottomSheet, new Activity, separate input field or fake shell emulator.
 * PTY and model tools both use the same native TerminalService / Alpine installation.
 */
internal class InChatTerminal(private val app: Application) {
    @Volatile private var service: TerminalService? = null
    @Volatile private var session: TerminalService.Session? = null
    private var connection: ServiceConnection? = null

    suspend fun connect() {
        if (service != null && session != null) return
        withContext(Dispatchers.IO) { LinuxEnvironment.install(app) }
        val ready = CompletableDeferred<TerminalService>()
        val bridge = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val backend = (binder as? TerminalService.LocalBinder)?.service
                if (backend == null) ready.completeExceptionally(
                    IllegalStateException("لم تتصل الطرفية الفعلية"))
                else if (!ready.isCompleted) ready.complete(backend)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                session = null
                if (!ready.isCompleted) ready.completeExceptionally(
                    IllegalStateException("توقف محرك الطرفية"))
            }
        }
        val intent = Intent(app, TerminalService::class.java)
        val bound = withContext(Dispatchers.Main) {
            app.startForegroundService(intent)
            app.bindService(intent, bridge, Context.BIND_AUTO_CREATE)
        }
        if (!bound) throw IllegalStateException("تعذر الاتصال بالطرفية")
        connection = bridge
        try {
            val backend = withTimeout(20000) { ready.await() }
            val shell = withContext(Dispatchers.IO) { backend.openChatSession() }
            service = backend
            session = shell
        } catch (e: Exception) {
            disconnect()
            throw e
        }
    }

    fun writeLine(line: String) {
        val backend = service ?: throw IllegalStateException("الطرفية لم تبدأ")
        val shell = session ?: throw IllegalStateException("الجلسة غير جاهزة")
        backend.write(shell, line + "\n")
    }

    fun sendControl(raw: String) {
        val backend = service ?: return
        val shell = session ?: return
        backend.write(shell, raw)
    }

    fun screen(): String {
        val backend = service ?: return "الطرفية غير متصلة"
        val shell = session ?: return "الجلسة غير جاهزة"
        return backend.renderChatSession(shell).trimEnd().takeLast(7500)
    }

    fun configureAgent(providerId: String) {
        require(providerId in setOf("freellmpool", "gpt4free", "free-llm-gateway", "free-llm-api"))
        val folder = File(LinuxEnvironment.rootfs(app), "root/.m4-agent")
        check(folder.isDirectory || folder.mkdirs()) { "تعذر إنشاء إعدادات الوكيل" }
        val file = File(folder, "provider.env")
        file.writeText("export M4_GATEWAY_FAMILY=$providerId\n")
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    fun disconnect() {
        service = null
        session = null
        val previous = connection
        connection = null
        if (previous != null) {
            try { app.unbindService(previous) } catch (_: IllegalArgumentException) { }
        }
        // Detaching the chat must not kill the running PTY command.
    }
}
