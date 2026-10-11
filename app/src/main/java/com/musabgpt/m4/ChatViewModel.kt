package com.musabgpt.m4

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.musabgpt.m4.terminal.EmbeddedAlpineBackend
import com.musabgpt.m4.phone.PhoneIntent
import com.musabgpt.m4.phone.AndroidPhoneTools
import com.musabgpt.m4.runtime.ChatEventStream
import com.musabgpt.m4.runtime.CapabilityBroker

data class UiMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,
    val content: String,
    val turnId: String = "",
    val attachments: List<String> = emptyList()
)
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "محادثة جديدة",
    val messages: List<UiMessage> = emptyList()
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    val terminal = EmbeddedAlpineBackend(app, viewModelScope)
    private val prefs = app.getSharedPreferences("m4_chats", 0)
    // Four built-in source families only. No local model import, custom API or keys.
    private val _providers = MutableStateFlow(ProviderCatalog.all)
    val providers = _providers.asStateFlow()
    private val _providerIndex = MutableStateFlow(prefs.getInt("provider", 0)
        .coerceIn(0, ProviderCatalog.all.lastIndex))
    val providerIndex = _providerIndex.asStateFlow()
    private val _activeRoute = MutableStateFlow("")
    val activeRoute = _activeRoute.asStateFlow()
    private val _permissionMode = MutableStateFlow(prefs.getString("permission_mode", "auto_project")
        ?.takeIf { it in setOf("auto_project", "plan_only", "ask_always", "accept_edits") } ?: "auto_project")
    val permissionMode = _permissionMode.asStateFlow()
    private val _requestedPanel = MutableStateFlow<String?>(null)
    val requestedPanel = _requestedPanel.asStateFlow()
    fun consumedPanel() { _requestedPanel.value = null }
    private val _pendingAttachments = MutableStateFlow(emptyList<com.musabgpt.m4.artifacts.ChatArtifact>())
    val pendingAttachments = _pendingAttachments.asStateFlow()
    fun attach(uri: android.net.Uri) { viewModelScope.launch {
        try {
            val artifact = withContext(Dispatchers.IO) { com.musabgpt.m4.artifacts.ArtifactRepository.importDocument(getApplication(), _currentId.value, uri) }
            _pendingAttachments.value = (_pendingAttachments.value + artifact).distinctBy { it.id }.takeLast(8)
        } catch (e: Exception) { edit(_currentId.value) { it.copy(messages = it.messages + UiMessage(role = "assistant", content = "تعذر استيراد المرفق: ${e.message}")) }; saveHistory() }
    } }
    fun attachCamera(bitmap: android.graphics.Bitmap) {
        viewModelScope.launch {
            try {
                val owner = _currentId.value
                val item = withContext(Dispatchers.IO) {
                    val stream = java.io.ByteArrayOutputStream()
                    require(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)) { "تعذر التقاط الصورة" }
                    com.musabgpt.m4.artifacts.ArtifactRepository.saveBinary(
                        getApplication(), owner, "camera-"+System.currentTimeMillis()+".jpg", "image/jpeg", stream.toByteArray()
                    )
                }
                _pendingAttachments.value = (_pendingAttachments.value + item).distinctBy { it.id }.takeLast(8)
            } catch (e: Exception) {
                edit(_currentId.value) { it.copy(messages = it.messages + UiMessage(role = "assistant", content = "تعذر إرفاق الصورة: "+e.message)) }
                saveHistory()
            }
        }
    }
    fun removeAttachment(id: String) { _pendingAttachments.value = _pendingAttachments.value.filter { it.id != id } }
    fun saveCode(language: String, code: String) { viewModelScope.launch {
        try { withContext(Dispatchers.IO) {
            val extension = when (language.lowercase()) { "python", "py" -> "py"; "bash", "shell", "sh" -> "sh"; "html" -> "html"; "json" -> "json"; "markdown", "md" -> "md"; else -> "txt" }
            com.musabgpt.m4.artifacts.ArtifactRepository.saveText(getApplication(), _currentId.value, "code-${System.currentTimeMillis()}.$extension", code)
        } } catch (e: Exception) { edit(_currentId.value) { it.copy(messages = it.messages + UiMessage(role = "assistant", content = "تعذر حفظ الملف: ${e.message}")) }; saveHistory() }
    } }
    fun setPlanMode(plan: Boolean) {
        _permissionMode.value = if (plan) "plan_only" else "auto_project"
        prefs.edit().putString("permission_mode", _permissionMode.value).apply()
    }
    fun setPermissionMode(mode: String) {
        require(mode in setOf("auto_project", "plan_only", "ask_always", "accept_edits"))
        _permissionMode.value = mode
        prefs.edit().putString("permission_mode", mode).apply()
    }
    private var currentTurn: String? = null
    fun pause() { currentTurn?.let { terminal.control(it, "pause") } }
    fun resume() { currentTurn?.let { terminal.control(it, "resume") } }
    fun steer(text: String) { currentTurn?.let { terminal.control(it, "steer", text) } }
    init {
        com.musabgpt.m4.runtime.AgentControl.onStop = { stop() }
        // Legacy configuration has no meaning and may contain old API secrets.
        val editor = prefs.edit()
        for (old in listOf("Kilo Auto Free", "Groq", "Gemini", "OpenRouter", "Custom")) {
            editor.remove("url_" + old).remove("model_" + old).remove("key_" + old)
        }
        editor.apply()
    }
    private val _conversations = MutableStateFlow(restoreConversations())
    val conversations = _conversations.asStateFlow()
    private val _currentId = MutableStateFlow(prefs.getString("active_conversation", null)
        ?.takeIf { id -> _conversations.value.any { it.id == id } } ?: _conversations.value.first().id)
    val currentId = _currentId.asStateFlow()
    private val _streaming = MutableStateFlow(false)
    val streaming = _streaming.asStateFlow()
    private var job: Job? = null
    val selected: Provider get() = _providers.value[_providerIndex.value]

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
                        UiMessage(id = m.getString("id"), role = m.getString("role"), content = m.getString("content"), turnId = m.optString("turn_id"), attachments = m.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList())
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
                messages.put(JSONObject().put("id", m.id).put("role", m.role).put("content", m.content.take(40000)).put("turn_id", m.turnId).put("attachments", JSONArray(m.attachments)))
            }
            array.put(JSONObject().put("id", c.id).put("title", c.title).put("messages", messages))
        }
        prefs.edit().putString("history", array.toString()).putString("active_conversation", _currentId.value).apply()
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
        _pendingAttachments.value = emptyList()
        saveHistory()
    }
    fun select(id: String) {
        if (_streaming.value) return
        if (_conversations.value.any { it.id == id }) {
            _currentId.value = id
            _pendingAttachments.value = emptyList()
            prefs.edit().putString("active_conversation", id).apply()
        }
    }
    fun rename(id: String, title: String) { if (!_streaming.value && title.isNotBlank()) { edit(id) { it.copy(title = title.trim().take(100)) }; saveHistory() } }
    fun delete(id: String) {
        if (_streaming.value) return
        com.musabgpt.m4.runtime.CapabilityBroker.revoke(id)
        _conversations.value = _conversations.value.filter { it.id != id }.ifEmpty { listOf(Conversation()) }
        if (_currentId.value == id) _currentId.value = _conversations.value.first().id
        saveHistory()
    }
    fun fork(messageId: String? = null, editText: String? = null) {
        if (_streaming.value) return
        val source = _conversations.value.first { it.id == _currentId.value }
        val at = messageId?.let { id -> source.messages.indexOfFirst { it.id == id } } ?: source.messages.lastIndex
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            val retained = source.messages.take(if (editText == null) at + 1 else at)
            val mapping = withContext(Dispatchers.IO) { com.musabgpt.m4.artifacts.ArtifactRepository.fork(getApplication(), source.id, id, retained.flatMap { it.attachments }.toSet()) }
            val messages = source.messages.take(if (editText == null) at + 1 else at).map { it.copy(id = UUID.randomUUID().toString(), attachments = it.attachments.mapNotNull { old -> mapping[old] }) }
            _conversations.value = listOf(Conversation(id, source.title + " • فرع", messages)) + _conversations.value
            _currentId.value = id; saveHistory()
            if (editText != null) send(editText)
        }
    }
    fun exportChat() { viewModelScope.launch {
        val c = _conversations.value.first { it.id == _currentId.value }
        withContext(Dispatchers.IO) { com.musabgpt.m4.artifacts.ArtifactRepository.saveReport(getApplication(), c.id, "conversation.md", "# ${c.title}\n\n" + c.messages.joinToString("\n\n") { "## ${it.role}\n${it.content}" }) }
    } }
    fun rewind(turnId: String) { if (_streaming.value || turnId.isBlank()) return
        val owner = _currentId.value
        _streaming.value = true
        job = viewModelScope.launch {
            try {
                val request = JSONObject().put("owner_session_id", owner).put("turn_id", UUID.randomUUID().toString()).put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "/rewind $turnId")))
                val result = terminal.chat(request, selected.id, _permissionMode.value) {}
                val value = runCatching { JSONObject(result.getString("content")) }.getOrNull()
                if (value?.optString("status") == "rewound") {
                    val messages = _conversations.value.first { it.id == owner }.messages
                    val ids = messages.dropWhile { it.turnId != turnId }.filter { it.role == "assistant" }.flatMap { it.attachments }
                    withContext(Dispatchers.IO) { com.musabgpt.m4.artifacts.ArtifactRepository.archive(getApplication(), owner, ids) }
                    edit(owner) { c -> c.copy(messages = c.messages.takeWhile { it.turnId != turnId }) }
                }
                else edit(owner) { c -> c.copy(messages = c.messages + UiMessage(role = "assistant", content = result.optString("content", "تعذر التراجع"))) }
            } catch (e: Exception) { edit(owner) { c -> c.copy(messages = c.messages + UiMessage(role = "assistant", content = "تعذر التراجع: ${e.message}")) } }
            finally { _streaming.value = false; saveHistory() }
        }
    }
    fun stop() {
        CapabilityBroker.cancel()
        terminal.interruptAgent()
        job?.cancel()
    }

    private fun edit(id: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map { if (it.id == id) transform(it) else it }
    }

    fun recordTerminalRequest(text: String) {
        edit(_currentId.value) { c -> c.copy(messages = c.messages + UiMessage(role = "user", content = text)) }
        saveHistory()
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _streaming.value) return
        if (clean in setOf("/terminal", "/phone", "/browser", "/github", "/tasks", "/termux", "/schedule", "/tools", "/files")) {
            _requestedPanel.value = when (clean) {
                "/schedule" -> "tasks"
                "/termux" -> "terminal" // Opens M4\'s integrated Linux terminal; /doctor identifies the actual engine.
                "/phone" -> "tools"
                else -> clean.drop(1)
            }
            return
        }
        if (clean == "/doctor") {
            val owner = _currentId.value
            val answer = UiMessage(role = "assistant", content = "جارٍ فحص الأدوات المحلية فعلياً...")
            edit(owner) { conversation ->
                conversation.copy(messages = conversation.messages + UiMessage(role = "user", content = clean) + answer)
            }
            saveHistory()
            _streaming.value = true
            job = viewModelScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        com.musabgpt.m4.terminal.RuntimeDoctor.inspect(getApplication()) {
                            job?.isCancelled == true
                        }
                    }
                    edit(owner) { c ->
                        c.copy(messages = c.messages.map {
                            if (it.id == answer.id) it.copy(content = result.report) else it
                        })
                    }
                } catch (cancel: CancellationException) {
                    edit(owner) { c ->
                        c.copy(messages = c.messages.map {
                            if (it.id == answer.id) it.copy(content = "تم إيقاف الفحص.") else it
                        })
                    }
                } catch (failure: Exception) {
                    edit(owner) { c ->
                        c.copy(messages = c.messages.map {
                            if (it.id == answer.id) it.copy(content = "فشل الفحص المحلي: " + failure.message) else it
                        })
                    }
                } finally {
                    _streaming.value = false
                    saveHistory()
                }
            }
            return
        }
        if (clean == "/export") { exportChat(); return }
        if (clean == "/compact") {
            edit(_currentId.value) { c -> if (c.messages.size <= 20) c else c.copy(messages = listOf(c.messages.first(), UiMessage(role = "assistant", content = "سياق مضغوط: الهدف الأصلي أعلاه؛ حُذفت ${c.messages.size - 17} رسالة من سياق النموذج. المرفقات وسجل الأدوات باقية.\n" + c.messages.filter { it.role == "user" }.takeLast(4).joinToString("\n") { it.content.take(500) })) + c.messages.takeLast(16)) }
            saveHistory(); return
        }
        if (clean.startsWith("/permissions ")) {
            val mode = clean.substringAfter(' ').trim()
            if (mode in setOf("auto_project", "ask_always", "accept_edits", "plan_only")) setPermissionMode(mode)
            else { edit(_currentId.value) { c -> c.copy(messages = c.messages + UiMessage(role = "assistant", content = "وضع صلاحيات غير معروف. الخيارات: auto_project، ask_always، accept_edits، plan_only")) }; saveHistory() }
            return
        }
        if (clean in setOf("/help", "/plan", "/permissions", "/clear")) {
            if (clean == "/clear") { newChat(); return }
            if (clean == "/plan") setPlanMode(_permissionMode.value != "plan_only")
            val result = when (clean) {
                "/help" -> "اكتب طلبك مباشرة؛ المحادثة تستخدم أدوات الهاتف والملفات والطرفية. ! ينفذ أمرًا صريحًا. /plan للتخطيط، /permissions للصلاحيات، /tools للأدوات، /doctor للقدرات، /clear لمحادثة جديدة."
                "/permissions" -> "الوضع الحالي: ${_permissionMode.value}\nاختر من قائمة الصلاحيات: auto_project، ask_always، accept_edits، plan_only. كل إجراء حساس للهاتف أو النشر يطلب موافقة محددة."
                else -> if (_permissionMode.value == "plan_only") "وضع الوكيل: تخطيط للقراءة فقط" else "وضع الوكيل: تنفيذ داخل المشروع"
            }
            edit(_currentId.value) { c -> c.copy(messages = c.messages + UiMessage(role = "user", content = clean) + UiMessage(role = "assistant", content = result)) }
            saveHistory()
            return
        }
        val convId = _currentId.value
        val turnId = UUID.randomUUID().toString()
        val attached = _pendingAttachments.value
        _pendingAttachments.value = emptyList()
        val reply = UiMessage(role = "assistant", content = "", turnId = turnId)
        edit(convId) { c ->
            c.copy(title = if (c.messages.isEmpty()) clean.take(35) else c.title,
                   messages = c.messages + UiMessage(role = "user", content = clean, turnId = turnId, attachments = attached.map { it.id }) + reply)
        }
        saveHistory()
        val conv = _conversations.value.first { it.id == convId }
        val history = JSONArray().apply { conv.messages.dropLast(1).takeLast(32).forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }
        val primary = selected
        currentTurn = turnId
        _activeRoute.value = ""
        _streaming.value = true
        job = viewModelScope.launch {
            try {
                val target = if (attached.isEmpty()) PhoneIntent.openTarget(clean) else null
                val result = if (target != null && _permissionMode.value != "plan_only") {
                    val args = JSONObject().put("app", target)
                    withContext(Dispatchers.IO) { ChatEventStream.append(getApplication(), convId, turnId, "tool_started", JSONObject().put("tool", "PhoneOpenApp").put("args", args)) }
                    val allowed = _permissionMode.value != "ask_always" || withContext(Dispatchers.IO) { CapabilityBroker.request(convId, "فتح تطبيق", args) }
                    val observed = if (allowed) AndroidPhoneTools.openApp(getApplication(), target)
                        else JSONObject().put("status", "denied").put("error", "رفضت فتح التطبيق")
                    withContext(Dispatchers.IO) { ChatEventStream.append(getApplication(), convId, turnId, "tool_finished", JSONObject().put("tool", "PhoneOpenApp").put("args", args).put("result", observed.toString()).put("ok", observed.optString("status") == "dispatched")) }
                    JSONObject().put("content", observed.optString("message", observed.optString("error", "تعذر فتح التطبيق")))
                } else {
                    val request = JSONObject().put("owner_session_id", convId).put("turn_id", turnId).put("messages", history)
                        .put("attachments", JSONArray(attached.map { it.json().put("path", ".m4/imports/${it.id}/${it.name}") }))
                    terminal.chat(request, primary.id, _permissionMode.value) { event ->
                        if (event.kind == "provider_route") {
                            _activeRoute.value = "المسار الفعلي: " + event.result
                        } else if (event.kind == "token") edit(convId) { c ->
                            c.copy(messages = c.messages.map { if (it.id == reply.id) it.copy(content = it.content + event.result) else it })
                        }
                    }
                }
                val artifacts = result.optJSONArray("artifacts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("artifact_id")?.takeIf { id -> id.isNotBlank() } } } ?: emptyList()
                val status = result.optString("status")
                val content = result.optString("content", "لا توجد نتيجة مسجلة") + if (status in setOf("blocked", "failed", "canceled", "paused-step-limit")) "\nحالة المهمة المسجلة: $status" else ""
                edit(convId) { c -> c.copy(messages = c.messages.map { if (it.id == reply.id) it.copy(content = content, attachments = artifacts) else it }) }
            } catch (cancel: CancellationException) {
                edit(convId) { c ->
                    c.copy(messages = c.messages.map {
                        if (it.id == reply.id && it.content.isEmpty()) it.copy(content = "تم إيقاف الرد") else it
                    })
                }
            } catch (e: Exception) {
                edit(convId) { c ->
                    c.copy(messages = c.messages.map {
                        if (it.id == reply.id) it.copy(content = it.content +
                            "\nتعذر إكمال الرد: " + (e.message ?: "فشل الاتصال")) else it
                    })
                }
            } finally {
                _streaming.value = false
                currentTurn = null
                saveHistory()
                com.musabgpt.m4.scheduler.TaskScheduler.event(getApplication(), "chat_finished", turnId, convId)
            }
        }
    }

    override fun onCleared() {
        stop()
        terminal.detach()
        super.onCleared()
    }
}
