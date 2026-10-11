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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import com.musabgpt.m4.runtime.ChatEventStream
import com.musabgpt.m4.runtime.RuntimeEvent
import java.io.File
import java.util.UUID
import android.util.Log

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
    private var chatSession: TerminalService.Session? = null
    private var activeTurn: String? = null
    private var initialized = kotlinx.coroutines.CompletableDeferred<Unit>()
    private var preparing = false
    private val mutable = MutableStateFlow(TerminalSnapshot())
    val state = mutable.asStateFlow()
    private val observer = Runnable { refresh() }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as TerminalService.LocalBinder).service
            bound = true
            binding = false
            service?.addListener(observer)
            prepare()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            binding = false
            mutable.value = TerminalSnapshot(status = "انقطع اتصال خدمة الطرفية؛ يمكن إعادة فتحها")
        }
    }

    fun open() {
        if (bound) { if (initialized.isCancelled) { initialized = kotlinx.coroutines.CompletableDeferred(); prepare() }; return }
        if (binding) return
        initialized = kotlinx.coroutines.CompletableDeferred()
        try {
            val intent = Intent(context, TerminalService::class.java)
            context.startForegroundService(intent)
            binding = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!binding) error("تعذر ربط خدمة الطرفية")
            mutable.value = mutable.value.copy(status = "جارٍ ربط الطرفية", preparing = true)
        } catch (e: Exception) { failure(e.message) }
    }

    private fun prepare() {
        if (preparing) return
        preparing = true
        mutable.value = mutable.value.copy(status = "جارٍ تجهيز Alpine المدمجة", preparing = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LinuxEnvironment.install(context.applicationContext)
                    io.musab.arabicterminal.ProjectBootstrap.ensure(LinuxEnvironment.rootfs(context))
                }
                if (service == null) error("انقطع اتصال الطرفية أثناء التجهيز")
                if (service?.sessions()?.none { it.isLinux && !it.isFinished } == true) {
                    chatSession = service?.create(false, true, 24, 80)
                } else {
                    chatSession = service?.sessions()?.firstOrNull { it.isLinux && !it.isFinished }
                }
                preparing = false
                initialized.complete(Unit)
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
            sessions = current.sessions().toList(), selected = selected, frame = selected?.frame(), preparing = preparing
        )
    }

    private fun failure(message: String?) {
        preparing = false
        initialized.completeExceptionally(java.io.IOException(message ?: "تعذر تجهيز الطرفية"))
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
    fun interruptAgent() {
        activeTurn?.let { turn -> control(turn, "stop") }
        service?.write(chatSession, "\u0003")
    }
    fun control(turn: String, action: String, text: String = "") {
        require(action in setOf("pause", "resume", "stop", "steer"))
        val directory = File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent")
        if (!directory.isDirectory) return
        val temporary = File(directory, "control.json.tmp")
        temporary.writeText(JSONObject().put("turn_id", turn).put("action", action).put("text", text.take(6000)).toString())
        temporary.renameTo(File(directory, "control.json"))
    }
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

    /** A normal chat turn uses the real packaged agent, not a separate text API. */
    suspend fun chat(request: JSONObject, family: String, mode: String, event: (RuntimeEvent) -> Unit): JSONObject {
        require(family in setOf("freellmpool", "gpt4free", "free-llm-gateway", "free-llm-api"))
        require(mode in setOf("auto_project", "plan_only", "ask_always", "accept_edits"))
        open()
        withTimeout(120000) { initialized.await() }
        val current = service ?: error("لم تتصل خدمة الطرفية")
        require(LinuxEnvironment.installed(context)) { state.value.status }
        val owner = request.getString("owner_session_id")
        val turn = request.getString("turn_id")
        require(owner.matches(Regex("[A-Za-z0-9-]{1,80}")) && turn.matches(Regex("[A-Za-z0-9-]{1,80}")))
        val directory = File(LinuxEnvironment.rootfs(context), "root/.m4/chats/$owner/$turn")
        withContext(Dispatchers.IO) {
            com.musabgpt.m4.artifacts.ArtifactRepository.syncImports(context, owner)
            directory.mkdirs(); File(directory, "request.json").writeText(request.toString())
        }
        val session = chatSession?.takeIf { !it.isFinished && current.sessions().contains(it) }
            ?: current.create(false, true, 24, 80).also { chatSession = it }
        Log.e("M4Runtime", "chat session=${session.name} project=${File(LinuxEnvironment.rootfs(context), "root/projects/M4/.git").isDirectory}")
        awaitShellReady(current, session)
        val config = File(directory, "provider.env")
        config.writeText("export M4_GATEWAY_FAMILY=${quote(family)}\nexport M4_OWNER_SESSION=${quote(owner)}\nexport M4_TURN_ID=${quote(turn)}\nexport M4_PERMISSION_MODE=${quote(mode)}\n")
        config.setReadable(false, false); config.setWritable(false, false)
        config.setReadable(true, true); config.setWritable(true, true)
        activeTurn = turn
        val guestDirectory = "/root/.m4/chats/$owner/$turn"
        Log.e("M4Runtime", "sending agent command family=$family path=$guestDirectory")
        current.write(session, "M4_AGENT_PROVIDER_FILE=${quote("$guestDirectory/provider.env")} m4-agent-start --chat ${quote("$guestDirectory/request.json")}\n")
        var last = 0L
        try {
            return withTimeout(660000) {
                while (true) {
                    val rows = withContext(Dispatchers.IO) { ChatEventStream.read(context, owner, turn, 300) }
                    rows.filter { it.id > last }.forEach { row -> event(row); last = maxOf(last, row.id) }
                    val response = withContext(Dispatchers.IO) {
                        File(directory, "response.json").takeIf { it.isFile && it.length() in 1..1500000 }?.readText()
                    }
                    if (response != null) return@withTimeout JSONObject(response)
                    if (session.isFinished) error("انتهت جلسة الوكيل قبل حفظ النتيجة؛ راجع الطرفية")
                    delay(180)
                }
                @Suppress("UNREACHABLE_CODE") JSONObject()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            control(turn, "stop")
            current.write(session, "\u0003")
            throw e
        } finally { activeTurn = null; config.delete() }
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
            val configName = "provider-" + UUID.randomUUID().toString() + ".env"
            val config = File(directory, configName)
            val mode = context.getSharedPreferences("m4_chats", 0).getString("permission_mode", "auto_project")
                ?.takeIf { it in setOf("auto_project", "plan_only") } ?: "auto_project"
            config.writeText("export M4_GATEWAY_FAMILY=${quote(families[index])}\nexport M4_OWNER_SESSION=${quote(owner)}\nexport M4_PERMISSION_MODE=${quote(mode)}\n")
            config.setReadable(false, false); config.setWritable(false, false)
            config.setReadable(true, true); config.setWritable(true, true)
            current.write(session, "M4_AGENT_PROVIDER_FILE=${quote("/root/.m4-agent/$configName")} m4-agent-start ${quote(goal)}\n")
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

    /** A newly forked PRoot shell can take a moment to exec bash.  Writing the
     * agent command before that shell has read its first byte is racy on some
     * emulator kernels, so exchange a visible marker before starting a turn. */
    private suspend fun awaitShellReady(current: TerminalService, session: TerminalService.Session) {
        Log.e("M4Runtime", "waiting for Alpine shell marker session=${session.name}")
        current.write(session, "printf '__M4_SESSION_READY__\\n'\n")
        withTimeout(20000) {
            while (true) {
                if (session.isFinished) error("انتهت جلسة الطرفية أثناء تشغيل Alpine")
                if (session.frame()?.text?.contains("__M4_SESSION_READY__") == true) {
                    Log.e("M4Runtime", "Alpine shell marker received")
                    return@withTimeout
                }
                delay(100)
            }
        }
    }
