package com.musabgpt.m4

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Visible user approval is mandatory for arbitrary model-issued shell commands. */
data class ToolApproval(val command: String, val decision: CompletableDeferred<Boolean>)

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String
)
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "محادثة جديدة",
    val messages: List<UiMessage> = emptyList()
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("m4_chats", 0)
    // Four built-in source families only. No local model import, custom API or keys.
    private val _providers = MutableStateFlow(ProviderCatalog.all)
    val providers = _providers.asStateFlow()
    private val _providerIndex = MutableStateFlow(prefs.getInt("provider", 0)
        .coerceIn(0, ProviderCatalog.all.lastIndex))
    val providerIndex = _providerIndex.asStateFlow()
    private val _activeRoute = MutableStateFlow("")
    val activeRoute = _activeRoute.asStateFlow()
    init {
        // Legacy configuration has no meaning and may contain old API secrets.
        val editor = prefs.edit()
        for (old in listOf("Kilo Auto Free", "Groq", "Gemini", "OpenRouter", "Custom")) {
            editor.remove("url_" + old).remove("model_" + old).remove("key_" + old)
        }
        editor.apply()
    }
    private val _conversations = MutableStateFlow(restoreConversations())
    val conversations = _conversations.asStateFlow()
    private val _currentId = MutableStateFlow(_conversations.value.first().id)
    val currentId = _currentId.asStateFlow()
    private val _streaming = MutableStateFlow(false)
    val streaming = _streaming.asStateFlow()
    private var job: Job? = null
    private val nativeTools=M4NativeTools(app)
    // Alpine runs as a background engine. The chat composer never changes mode.
    private val inChatTerminal=InChatTerminal(app)
    private val _backgroundTaskRunning=MutableStateFlow(false)
    val backgroundTaskRunning=_backgroundTaskRunning.asStateFlow()
    private var terminalMonitorJob: Job?=null
    private var liveTerminalMessageId: String?=null
    private var backgroundConversationId: String?=null
    private var terminalReady=false
    private val queuedTerminalLines=ArrayDeque<String>()
    private fun sendTerminalLine(line: String) {
        if (!terminalReady) {
            queuedTerminalLines.addLast(line)
            return
        }
        if (line.startsWith("m4-agent-start ")) inChatTerminal.configureAgent(selected.id)
        inChatTerminal.writeLine(line)
    }
    private data class PendingWhatsApp(val action: String, val conversationId: String, val replyId: String)
    private var pendingWhatsApp: PendingWhatsApp?=null
    private val _contactsPermissionTicket=MutableStateFlow(0)
    val contactsPermissionTicket=_contactsPermissionTicket.asStateFlow()
    fun onContactsPermissionResult(allowed: Boolean) {
        val pending=pendingWhatsApp ?: return
        pendingWhatsApp=null
        viewModelScope.launch {
            val result=if(allowed) nativeTools.performQuick(pending.action)
                else "لم أتمكن من العثور على المستلم بدون إذن جهات الاتصال. يمكنك كتابة رقم الهاتف الدولي بدلاً من ذلك."
            edit(pending.conversationId) { conv -> conv.copy(messages=conv.messages.map {
                if(it.id==pending.replyId) it.copy(content=result) else it
            }) }
            saveHistory()
        }
    }
    private val prompt by lazy {
        app.assets.open("m4-chat-system.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private val _approval=MutableStateFlow<ToolApproval?>(null)
    val approval=_approval.asStateFlow()
    private val _terminalOutput=MutableStateFlow("")
    val terminalOutput=_terminalOutput.asStateFlow()
    private var taskApprovedCommands=0
    /**
     * Run user-requested interactive Alpine tasks IN a chat message, not as a
     * terminal mode, a sheet or a new screen. A final shell sentinel lets the
     * monitor stop when the command actually completes.
     */
    fun launchTerminal(command: String? = null) {
        if (command.isNullOrBlank()) return
        val convId=_currentId.value
        if (_backgroundTaskRunning.value) {
            edit(convId) { it.copy(messages=it.messages+UiMessage(role="assistant",
                content="هناك مهمة Alpine قيد التشغيل. انتظر اكتمالها أو أوقفها قبل بدء مهمة ثانية.")) }
            return
        }
        _backgroundTaskRunning.value=true
        backgroundConversationId=convId
        terminalReady=false
        queuedTerminalLines.clear()
        val marker="__M4_TASK_DONE_"+UUID.randomUUID().toString().replace("-","")+"__"
        val decorated=command+"; printf '\\n"+marker+":%s\\n' \"$?\""
        val terminalMessage=UiMessage(role="terminal",
            content="```sh\n⏳ جاري تجهيز Alpine Linux وتشغيل المهمة...\n```")
        liveTerminalMessageId=terminalMessage.id
        edit(convId) { it.copy(messages=it.messages+terminalMessage) }
        terminalMonitorJob?.cancel()
        terminalMonitorJob=viewModelScope.launch {
            try {
                inChatTerminal.connect()
                terminalReady=true
                if (command.startsWith("m4-agent-start ")) inChatTerminal.configureAgent(selected.id)
                // New run must not inherit the previous interactive terminal screen.
                inChatTerminal.writeLine("clear")
                inChatTerminal.writeLine(decorated)
                while (queuedTerminalLines.isNotEmpty()) sendTerminalLine(queuedTerminalLines.removeFirst())
                var lastScreen=""
                val completed=Regex("(?m)^"+Regex.escape(marker)+":([0-9]+)\\s*$")
                while (isActive && _backgroundTaskRunning.value) {
                    val screen=inChatTerminal.screen()
                    val status=completed.find(screen)
                    val display=screen.replace(completed,"").trimEnd().takeLast(6500)
                    if (display!=lastScreen || status!=null) {
                        lastScreen=display
                        val header=when {
                            status==null -> "⏳ تنفيذ Alpine جارٍ..."
                            status.groupValues[1]=="0" -> "✅ انتهى التنفيذ (exit 0)"
                            else -> "⚠️ انتهى التنفيذ (exit "+status.groupValues[1]+")"
                        }
                        val formatted=header+"\n```sh\n"+display+"\n```"
                        edit(convId) { conv -> conv.copy(messages=conv.messages.map {
                            if (it.id==terminalMessage.id) it.copy(content=formatted) else it
                        }) }
                    }
                    if (status!=null) {
                        _backgroundTaskRunning.value=false
                        break
                    }
                    delay(250)
                }
            } catch (_: CancellationException) {
                // The user cancelled the task.
            } catch (e: Exception) {
                edit(convId) { conv -> conv.copy(messages=conv.messages.map {
                    if (it.id==terminalMessage.id)
                        it.copy(content="تعذّر تنفيذ Alpine: "+(e.message?:"خطأ غير معروف"))
                    else it
                }) }
            } finally {
                terminalReady=false
                queuedTerminalLines.clear()
                _backgroundTaskRunning.value=false
                liveTerminalMessageId=null
                backgroundConversationId=null
                saveHistory()
            }
        }
    }

    fun stopBackgroundTask() {
        if (!_backgroundTaskRunning.value) return
        inChatTerminal.sendControl("\u0003")
        val id=liveTerminalMessageId
        if (id!=null) edit(backgroundConversationId ?: _currentId.value) { conv -> conv.copy(messages=conv.messages.map {
            if (it.id==id) it.copy(content=it.content+"\n⏹️ أوقف المستخدم المهمة.") else it
        }) }
        terminalMonitorJob?.cancel()
        terminalMonitorJob=null
        backgroundConversationId=null
        _backgroundTaskRunning.value=false
        terminalReady=false
        queuedTerminalLines.clear()
        saveHistory()
    }

    val selected: Provider get() = _providers.value[_providerIndex.value]

    fun decideTool(approved: Boolean, approveTask: Boolean = false) {
        val pending=_approval.value ?: return
        if(approved && approveTask) taskApprovedCommands=11 // first + up to 11 further calls
        if(!pending.decision.isCompleted)pending.decision.complete(approved)
        _approval.value=null
    }

    private fun restoreConversations(): List<Conversation> {
        return try {
            val arr = JSONArray(prefs.getString("history", "[]"))
            (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val items = c.optJSONArray("messages") ?: JSONArray()
                Conversation(
                    id = c.getString("id"),
                    title = c.optString("title", "محادثة جديدة"),
                    messages = (0 until items.length()).map { n ->
                        val m = items.getJSONObject(n)
                        UiMessage(id = m.getString("id"), role = m.getString("role"), content = m.getString("content"))
                    }
                )
            }.ifEmpty { listOf(Conversation()) }
        } catch (_: Exception) { listOf(Conversation()) }
    }

    private fun saveHistory() {
        val array = JSONArray()
        _conversations.value.take(30).forEach { c ->
            val messages = JSONArray()
            c.messages.takeLast(100).forEach { m ->
                messages.put(JSONObject().put("id", m.id).put("role", m.role).put("content", m.content.take(40000)))
            }
            array.put(JSONObject().put("id", c.id).put("title", c.title).put("messages", messages))
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    fun selectProvider(index: Int) {
        if (_streaming.value || index !in _providers.value.indices) return
        _providerIndex.value = index
        prefs.edit().putInt("provider", index).apply()
    }

    fun newChat() {
        if (_streaming.value) return
        val c = Conversation()
        _conversations.value = listOf(c) + _conversations.value
        _currentId.value = c.id
        saveHistory()
    }
    fun select(id: String) {
        if (_streaming.value) return
        if (_conversations.value.any { it.id == id }) _currentId.value = id
    }
    fun stop() {
        decideTool(false)
        taskApprovedCommands=0
        ChatApi.stop()
        nativeTools.cancelTerminalCommand()
        job?.cancel()
    }

    private fun edit(id: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map { if (it.id == id) transform(it) else it }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _streaming.value) return
        if (clean.startsWith("!")) {
            val cmd=clean.removePrefix("!").trim()
            if (cmd.isNotBlank()) {
                val id=_currentId.value
                edit(id) { it.copy(messages=it.messages+
                    UiMessage(role="user",content=clean)) }
                if (_backgroundTaskRunning.value) {
                    edit(id) { it.copy(messages=it.messages+UiMessage(role="assistant",
                        content="هناك مهمة أخرى تعمل الآن؛ لن أمرّر الأمر إلى عملية تفاعلية قد تتلقى الإدخال خطأً. أوقف المهمة أو انتظر اكتمالها.")) }
                } else launchTerminal(cmd)
            }
            return
        }
        val convId = _currentId.value
        val reply = UiMessage(role = "assistant", content = "")
        edit(convId) { c ->
            c.copy(title = if (c.messages.isEmpty()) clean.take(35) else c.title,
                   messages = c.messages + UiMessage(role = "user", content = clean) + reply)
        }
        saveHistory()
        val conv = _conversations.value.first { it.id == convId }
        val history = conv.messages.dropLast(1).map { ChatMessage(it.role, it.content) }
        taskApprovedCommands=0
        _terminalOutput.value=""
        val primary=selected
        var toolLogMessageId: String?=null
        val quick=M4NativeTools.quickAction(clean)
        _activeRoute.value=if(quick!=null) "أدوات أندرويد المحلية" else "جاري الاتصال..."
        _streaming.value=true
        job=viewModelScope.launch {
            val buffer=StringBuilder()
            var lastFlush=0L
            fun flush() {
                if(buffer.isEmpty())return
                val portion=buffer.toString()
                buffer.setLength(0)
                edit(convId) { c -> c.copy(messages=c.messages.map {
                    if(it.id==reply.id)it.copy(content=it.content+portion) else it
                }) }
                lastFlush=android.os.SystemClock.elapsedRealtime()
            }
            try {
                if(quick!=null) {
                    if(quick=="github_connect") {
                        launchTerminal("gh auth login --hostname github.com --git-protocol https --web")
                        buffer.append("بدأت عملية ربط GitHub داخل المحادثة. لا يعتبر الحساب مرتبطًا حتى تؤكد نتيجة تسجيل الدخول الفعلية.")
                    } else if (quick.startsWith("whatsapp_draft:")) {
                        val draft=WhatsAppPlanner.fromAction(quick)
                        if (draft!=null && !WhatsAppPlanner.isPhone(draft.recipient) &&
                            getApplication<Application>().checkSelfPermission(Manifest.permission.READ_CONTACTS)
                                != PackageManager.PERMISSION_GRANTED) {
                            pendingWhatsApp=PendingWhatsApp(quick,convId,reply.id)
                            buffer.append("فهمت المهمة: إعداد رسالة واتساب إلى «")
                                .append(draft.recipient).append("» بنص «")
                                .append(draft.body).append("». أحتاج إذن جهات الاتصال للتأكد من رقم المستلم محلياً.")
                            _contactsPermissionTicket.value++
                        } else buffer.append(nativeTools.performQuick(quick))
                    } else buffer.append(nativeTools.performQuick(quick))
                } else {
                    ChatApi.stream(primary,history,prompt) { call ->
                        val shell=call.name=="terminal_command"
                        val approved=if(shell) {
                            if(taskApprovedCommands>0) {
                                taskApprovedCommands--
                                true
                            } else {
                                val command=call.arguments.optString("command","")
                                val choice=CompletableDeferred<Boolean>()
                                _approval.value=ToolApproval(command,choice)
                                try { withTimeout(120000) { choice.await() } }
                                finally { _approval.value=null }
                            }
                        } else false
                        if(call.name=="github_connect" &&
                           (clean.contains("github",ignoreCase=true) ||
                            clean.contains("جيت هاب") || clean.contains("غيتهاب"))) {
                            launchTerminal("gh auth login --hostname github.com --git-protocol https --web")
                            "بدأ ربط GitHub عبر Alpine في المحادثة؛ لم يُتحقق من المصادقة بعد."
                        } else nativeTools.execute(call.name,call.arguments,clean,approved) { chunk ->
                            // Genuine PTY stdout, captured while the command is running.
                            val limited=chunk.takeLast(1500)
                            if(limited.isNotBlank()) {
                                _terminalOutput.value=(_terminalOutput.value+limited).takeLast(8000)
                                val rendered="```sh\n"+_terminalOutput.value.replace("```","'''")+"\n```"
                                val logId=toolLogMessageId
                                if(logId==null) {
                                    val entry=UiMessage(role="terminal", content=rendered)
                                    toolLogMessageId=entry.id
                                    edit(convId) { it.copy(messages=it.messages+entry) }
                                } else edit(convId) { c -> c.copy(messages=c.messages.map {
                                    if(it.id==logId) it.copy(content=rendered) else it
                                }) }
                                _activeRoute.value="Alpine: "+limited.takeLast(80).replace("\n"," ")
                            }
                        }
                    }.collect { event ->
                        when(event) {
                            is ChatEvent.Route -> _activeRoute.value="يعمل عبر: "+event.name
                            is ChatEvent.Text -> {
                                buffer.append(event.value)
                                val now=android.os.SystemClock.elapsedRealtime()
                                if(buffer.length>=180 || now-lastFlush>85)flush()
                            }
                            is ChatEvent.ToolStatus -> {
                                flush()
                                buffer.append("\n\n🔧 ").append(event.text).append("\n")
                                flush()
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                if(buffer.isEmpty() && reply.content.isEmpty())buffer.append("تم إيقاف التنفيذ.")
            } catch(e: Exception) {
                buffer.append("\n")
                buffer.append("تعذّرت المهمة: ").append((e.message ?: "فشل الاتصال").take(300))
            } finally {
                flush()
                taskApprovedCommands=0
                _approval.value=null
                _streaming.value=false
                saveHistory()
            }
        }
    }

    override fun onCleared() {
        stop()
        stopBackgroundTask()
        inChatTerminal.disconnect()
        super.onCleared()
    }
}
