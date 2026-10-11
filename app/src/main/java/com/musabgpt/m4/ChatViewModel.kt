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
    val content: String
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
        saveHistory()
    }
    fun select(id: String) {
        if (_streaming.value) return
        if (_conversations.value.any { it.id == id }) {
            _currentId.value = id
            prefs.edit().putString("active_conversation", id).apply()
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
        if (clean in setOf("/help", "/plan", "/permissions", "/clear")) {
            if (clean == "/clear") { newChat(); return }
            if (clean == "/plan") setPlanMode(_permissionMode.value != "plan_only")
            val result = when (clean) {
                "/help" -> "اكتب طلبك مباشرة؛ المحادثة تستخدم أدوات الهاتف والملفات والطرفية. ! ينفذ أمرًا صريحًا. /plan للتخطيط، /permissions للصلاحيات، /tools للأدوات، /doctor للقدرات، /clear لمحادثة جديدة."
                else -> if (_permissionMode.value == "plan_only") "وضع الوكيل: تخطيط للقراءة فقط" else "وضع الوكيل: تنفيذ داخل المشروع بعد موافقة التشغيل"
            }
            edit(_currentId.value) { c -> c.copy(messages = c.messages + UiMessage(role = "user", content = clean) + UiMessage(role = "assistant", content = result)) }
            saveHistory()
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
        val history = JSONArray().apply { conv.messages.dropLast(1).takeLast(32).forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }
        val primary = selected
        val turnId = UUID.randomUUID().toString()
        currentTurn = turnId
        _activeRoute.value = ""
        _streaming.value = true
        job = viewModelScope.launch {
            try {
                val target = PhoneIntent.openTarget(clean)
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
                    terminal.chat(request, primary.id, _permissionMode.value) { event ->
                        if (event.kind == "provider_route") {
                            _activeRoute.value = "المسار الفعلي: " + event.result
                        } else if (event.kind == "token") edit(convId) { c ->
                            c.copy(messages = c.messages.map { if (it.id == reply.id) it.copy(content = it.content + event.result) else it })
                        }
                    }
                }
                edit(convId) { c -> c.copy(messages = c.messages.map { if (it.id == reply.id) it.copy(content = result.optString("content", "لا توجد نتيجة مسجلة")) else it }) }
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
            }
        }
    }

    override fun onCleared() {
        stop()
        terminal.detach()
        super.onCleared()
    }
}
