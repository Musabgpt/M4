package com.musabgpt.m4.terminal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.TerminalScreen
import io.musab.arabicterminal.TerminalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class TerminalSnapshot(
    val status: String = "الطرفية جاهزة للفتح",
    val sessions: List<TerminalService.Session> = emptyList(),
    val selected: TerminalService.Session? = null,
    val frame: TerminalScreen.Frame? = null,
    val preparing: Boolean = false
)

/** Binds the existing real PTY service to Compose; no second Activity is launched. */
class EmbeddedAlpineBackend(private val context: Context, private val scope: CoroutineScope) {
    private var service: TerminalService? = null
    private var bound = false
    private var binding = false
    private var pendingGoal: Pair<String, String>? = null
    private var pendingCommand: String? = null
    private val mutable = MutableStateFlow(TerminalSnapshot())
    val state = mutable.asStateFlow()
    private val observer = Runnable { refresh() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TerminalService.LocalBinder).service
            bound = true
            binding = false
            service?.addListener(observer)
            if (service?.sessions()?.isEmpty() == true) prepare() else ready()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            binding = false
            mutable.value = TerminalSnapshot(status = "انقطع اتصال خدمة الطرفية؛ يمكن إعادة فتحها")
        }
    }

    fun open() {
        if (bound) { ready(); return }
        if (binding) return
        try {
            val intent = Intent(context, TerminalService::class.java)
            context.startForegroundService(intent)
            binding = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!binding) error("تعذر ربط خدمة الطرفية")
            mutable.value = mutable.value.copy(status = "جارٍ ربط الطرفية", preparing = true)
        } catch (e: Exception) { failure(e.message) }
    }

    private fun prepare() {
        mutable.value = mutable.value.copy(status = "جارٍ تجهيز Alpine المدمجة", preparing = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LinuxEnvironment.install(context.applicationContext) }
                if (service == null) return@launch
                service?.create(false, true, 24, 80)
                ready()
            } catch (e: Exception) { failure(e.message) }
            catch (e: LinkageError) { failure(e.message) }
        }
    }

    private fun ready() {
        refresh()
        pendingGoal?.let { (goal, owner) ->
            pendingGoal = null
            launchAgent(goal, owner)
        }
        pendingCommand?.let { command ->
            pendingCommand = null
            execute(command)
        }
    }

    private fun refresh() {
        val current = service ?: return
        val selected = current.selected()
        mutable.value = TerminalSnapshot(
            status = when { selected == null -> "لا توجد جلسة؛ أنشئ جلسة Alpine"
                selected.isFinished -> "انتهت جلسة PTY"
                selected.isLinux -> "Alpine • PRoot • PTY حي"
                else -> "جلسة أندرويد" },
            sessions = current.sessions().toList(), selected = selected, frame = selected?.frame()
        )
    }

    private fun failure(message: String?) {
        mutable.value = mutable.value.copy(status = "تعذرت الطرفية: ${message ?: "خطأ غير معروف"}", preparing = false)
        pendingGoal = null
        pendingCommand = null
    }

    fun newSession() {
        try {
            if (service == null) { open(); return }
            service?.create(false, true, 24, 80)
        } catch (e: Exception) { failure(e.message) }
        catch (e: LinkageError) { failure(e.message) }
    }
    fun select(session: TerminalService.Session) { service?.select(session) }
    fun sendInput(text: String) { service?.write(service?.selected(), text) }
    fun interrupt() { sendInput("\u0003") }
    fun closeSession() { service?.close(service?.selected()) }
    fun resize(rows: Int, columns: Int) { service?.resize(service?.selected(), rows, columns) }

    fun execute(command: String) {
        if (command.isBlank() || command.length > 6000) { failure("الأمر فارغ أو طويل جداً"); return }
        if (service?.selected() == null) { pendingCommand = command; open(); return }
        sendInput(command + "\n")
    }

    fun runAgent(goal: String, owner: String) {
        if (goal.isBlank() || goal.length > 6000) { failure("الهدف فارغ أو طويل جداً"); return }
        if (service == null) { pendingGoal = goal to owner; open(); return }
        launchAgent(goal, owner)
    }

    private fun launchAgent(goal: String, owner: String) {
        try {
            require(owner.matches(Regex("[A-Za-z0-9-]{1,80}")))
            val current = service ?: return
            val session = current.create(false, true, 24, 80)
            val index = context.getSharedPreferences("m4_chats", 0).getInt("provider", 0).coerceIn(0, 3)
            val families = listOf("freellmpool", "gpt4free", "free-llm-gateway", "free-llm-api")
            val directory = File(LinuxEnvironment.rootfs(context), "root/.m4-agent")
            directory.mkdirs()
            val config = File(directory, "provider.env")
            config.writeText("export M4_GATEWAY_FAMILY=${quote(families[index])}\nexport M4_OWNER_SESSION=${quote(owner)}\n")
            config.setReadable(false, false); config.setWritable(false, false)
            config.setReadable(true, true); config.setWritable(true, true)
            current.write(session, "m4-agent-start ${quote(goal)}\n")
        } catch (e: Exception) { failure(e.message) }
    }

    fun detach() {
        service?.removeListener(observer)
        if (bound || binding) context.unbindService(connection)
        service = null; bound = false; binding = false
    }
    companion object {
        fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    }
}
