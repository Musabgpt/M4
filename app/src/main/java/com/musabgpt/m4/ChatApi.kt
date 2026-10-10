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
data class Provider(
    val name: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String = ""
)

/**
 * NewAl-Cloud inspired provider routing with first-party Compose presentation.
 * Free providers have no guarantee of uptime, tool calls or unlimited quotas.
 */
object ChatApi {
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var stopped = false

    fun stop() {
        stopped = true
        connection?.disconnect()
    }

    fun stream(providers: List<Provider>, history: List<ChatMessage>): Flow<String> = flow {
        stopped = false
        val configured = providers.filter { it.name == "Kilo Auto Free" || it.apiKey.isNotBlank() }
        if (configured.isEmpty()) throw IllegalStateException("أضف مفتاح API أو استخدم النموذج المجاني")
        var error: Exception? = null
        for (provider in configured) {
            var emitted = false
            try {
                val base = provider.baseUrl.trimEnd('/')
                val endpoint = if (base.endsWith("/chat/completions")) base else base + "/chat/completions"
                val uri = URL(endpoint)
                val local = uri.host == "127.0.0.1" || uri.host == "localhost"
                if (uri.protocol != "https" && !(local && uri.protocol == "http")) {
                    throw IllegalArgumentException("يتطلب HTTPS أو خادم محلي موثوق")
                }
                val messages = JSONArray().put(JSONObject()
                    .put("role", "system")
                    .put("content", "You are MusabAI, a careful programming assistant. Reply in the user's language. Do not claim that code ran without evidence."))
                history.forEach { m ->
                    messages.put(JSONObject().put("role", m.role).put("content", m.content))
                }
                val body = JSONObject()
                    .put("model", provider.model)
                    .put("messages", messages)
                    .put("stream", true)
                val c = uri.openConnection() as HttpURLConnection
                connection = c
                try {
                    c.instanceFollowRedirects = false
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 15_000
                    c.readTimeout = 75_000
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    c.setRequestProperty("Accept", "text/event-stream")
                    if (provider.apiKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer " + provider.apiKey)
                    c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    if (c.responseCode !in 200..299) {
                        val response = c.errorStream?.bufferedReader()?.use { it.readText().take(450) } ?: ""
                        throw IllegalStateException("HTTP " + c.responseCode + " (" + provider.name + ") " + response)
                    }
                    c.inputStream.bufferedReader().use { reader ->
                        while (!stopped) {
                            val line = reader.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.substringAfter("data:").trim()
                            if (data == "[DONE]") break
                            val item = try { JSONObject(data) } catch (_: Exception) { continue }
                            val choices = item.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) continue
                            val delta = choices.optJSONObject(0)?.optJSONObject("delta")
                            val token = delta?.optString("content", "") ?: ""
                            if (token.isNotEmpty()) {
                                emitted = true
                                emit(token)
                            }
                        }
                    }
                } finally {
                    c.disconnect()
                    connection = null
                }
                if (stopped) throw CancellationException("تم إيقاف المحادثة")
                if (emitted) return@flow
                throw IllegalStateException("رد فارغ من " + provider.name)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (stopped) throw CancellationException("تم الإيقاف")
                if (emitted) throw e // Never splice two providers into one answer.
                error = e
            }
        }
        throw IllegalStateException(error?.message ?: "لا يوجد مزود متاح حالياً")
    }.flowOn(Dispatchers.IO)
}
