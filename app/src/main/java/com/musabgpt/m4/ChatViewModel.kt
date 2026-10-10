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
    private val _providers = MutableStateFlow(listOf(
        Provider("Kilo Auto Free", "https://api.kilo.ai/api/gateway", "kilo-auto/free"),
        Provider("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        Provider("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        Provider("OpenRouter", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free"),
        Provider("Custom", "https://example.com/v1", "your-model")
    ).map {
        it.copy(
            baseUrl = prefs.getString("url_" + it.name, it.baseUrl) ?: it.baseUrl,
            model = prefs.getString("model_" + it.name, it.model) ?: it.model,
            apiKey = prefs.getString("key_" + it.name, "") ?: ""
        )
    })
    val providers = _providers.asStateFlow()

    private val _providerIndex = MutableStateFlow(prefs.getInt("provider", 0).coerceIn(0, 4))
    val providerIndex = _providerIndex.asStateFlow()
    private val _conversations = MutableStateFlow(restoreConversations())
    val conversations = _conversations.asStateFlow()
    private val _currentId = MutableStateFlow(_conversations.value.first().id)
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
        prefs.edit().putString("history", array.toString()).apply()
    }

    fun selectProvider(index: Int) {
        if (_streaming.value || index !in _providers.value.indices) return
        _providerIndex.value = index
        prefs.edit().putInt("provider", index).apply()
    }

    fun configureProvider(index: Int, url: String, model: String, key: String) {
        if (_streaming.value || index !in _providers.value.indices) return
        val safeUrl = url.trim()
        if (!safeUrl.startsWith("https://") && !safeUrl.startsWith("http://127.0.0.1:")) return
        if (model.isBlank() || model.length > 256) return
        _providers.value = _providers.value.mapIndexed { i, old ->
            if (i == index) old.copy(baseUrl = safeUrl, model = model.trim(), apiKey = key.trim()) else old
        }
        // MODE_PRIVATE, local only; keys are never committed to GitHub.
        prefs.edit()
            .putString("key_" + _providers.value[index].name, key.trim())
            .putString("url_" + _providers.value[index].name, safeUrl)
            .putString("model_" + _providers.value[index].name, model.trim())
            .apply()
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
        ChatApi.stop()
        job?.cancel()
    }

    private fun edit(id: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map { if (it.id == id) transform(it) else it }
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || _streaming.value) return
        val convId = _currentId.value
        val reply = UiMessage(role = "assistant", content = "")
        edit(convId) { c ->
            c.copy(title = if (c.messages.isEmpty()) clean.take(35) else c.title,
                   messages = c.messages + UiMessage(role = "user", content = clean) + reply)
        }
        saveHistory()
        val conv = _conversations.value.first { it.id == convId }
        val history = conv.messages.dropLast(1).map { ChatMessage(it.role, it.content) }
        val primary = selected
        val fallback = _providers.value.filter { it != primary && it.apiKey.isNotBlank() }
        _streaming.value = true
        job = viewModelScope.launch {
            try {
                ChatApi.stream(listOf(primary) + fallback, history).collect { token ->
                    edit(convId) { c ->
                        c.copy(messages = c.messages.map {
                            if (it.id == reply.id) it.copy(content = it.content + token) else it
                        })
                    }
                }
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
                saveHistory()
            }
        }
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}
