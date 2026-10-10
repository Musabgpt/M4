package com.musabgpt.m4

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val content: String)
sealed interface ChatEvent {
    data class Text(val value: String) : ChatEvent
    data class Route(val name: String) : ChatEvent
}

/** Bounded, keyless streaming router. Never sends API credentials and never follows redirects. */
object ChatApi {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var stopped = false

    fun stop() {
        stopped = true
        connection?.disconnect()
    }

    fun stream(selected: Provider, history: List<ChatMessage>): Flow<ChatEvent> = flow {
        stopped = false
        var lastError = "المسارات المجانية غير متاحة"
        for (route in ProviderCatalog.routingOrder(selected)) {
            var emitted = false
            try {
                val base = URL(route.baseUrl)
                val loopback = base.host == "127.0.0.1" || base.host == "localhost"
                require(
                    (base.protocol == "https" && !route.localService) ||
                        (base.protocol == "http" && loopback && route.localService)
                ) { "غير مسموح الاتصال بعنوان غير آمن" }
                require(base.userInfo == null && base.ref == null && base.query == null) {
                    "عنوان بوابة غير صالح"
                }
                val endpoint = URL(route.baseUrl.trimEnd('/') + "/chat/completions")
                val messages = JSONArray().put(JSONObject()
                    .put("role", "system")
                    .put("content", "You are M4 MusabAI, a careful Arabic-friendly coding assistant. You can propose code but cannot claim to execute it from chat. Execution uses the Arabic terminal after explicit user confirmation. Do not invent test results."))
                history.takeLast(32).forEach { m ->
                    if (m.role == "user" || m.role == "assistant") {
                        messages.put(JSONObject().put("role", m.role)
                            .put("content", m.content.take(16000)))
                    }
                }
                val payload = JSONObject()
                    .put("model", route.model)
                    .put("messages", messages)
                    .put("stream", true).toString().toByteArray(Charsets.UTF_8)
                val c = endpoint.openConnection() as HttpURLConnection
                connection = c
                try {
                    c.instanceFollowRedirects = false
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = if (route.localService) 2000 else 10000
                    c.readTimeout = 45000
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.setRequestProperty("Accept", "text/event-stream")
                    c.outputStream.use { it.write(payload) }
                    if (c.responseCode !in 200..299) {
                        val code = c.responseCode
                        throw IllegalStateException(route.name + " HTTP " + code)
                    }
                    var totalChars = 0
                    c.inputStream.bufferedReader().use { reader ->
                        while (!stopped && totalChars < 100000) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val part = line.substring(5).trim()
                            if (part == "[DONE]") break
                            val item = try { JSONObject(part) } catch (_: Exception) { continue }
                            val choice = item.optJSONArray("choices")?.optJSONObject(0) ?: continue
                            val delta = choice.optJSONObject("delta")
                            val token = delta?.optString("content", "") ?: ""
                            if (token.isNotEmpty()) {
                                if (!emitted) emit(ChatEvent.Route(route.name))
                                emitted = true
                                totalChars += token.length
                                emit(ChatEvent.Text(token))
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                    connection = null
                }
                if (stopped) throw CancellationException("تم إيقاف الإجابة")
                if (emitted) return@flow
                throw IllegalStateException(route.name + ": استجابة فارغة")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stopped) throw CancellationException("تم الإيقاف")
                // Do not splice different models together after any partial answer.
                if (emitted) throw e
                lastError = (e.message ?: route.name + ": تعذّر الاتصال").take(240)
            }
        }
        throw IllegalStateException("تعذر الوصول إلى البوابات: " + lastError)
    }.flowOn(Dispatchers.IO)
}
