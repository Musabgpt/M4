package com.musabgpt.m4.github

import android.content.Context
import com.musabgpt.m4.runtime.CapabilityBroker
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Small GitLab REST client with an Android Keystore-backed personal token. */
class GitLabClient(private val context: Context) {
    private val settings = context.getSharedPreferences("m4_gitlab", 0)
    private val vault = CredentialVault(context, "gitlab")

    private fun host(): String = settings.getString("host", "https://gitlab.com").orEmpty().trimEnd('/')
    fun status() = JSONObject().put("connected", vault.read() != null)
        .put("host", host()).put("username", settings.getString("username", ""))

    fun configureHost(value: String) {
        val url = URL(value.trim().trimEnd('/'))
        require(url.protocol == "https" && url.userInfo == null && url.query == null && url.ref == null && (url.path.isEmpty() || url.path == "/")) { "يجب أن يكون خادم GitLab HTTPS بدون مسار" }
        settings.edit().putString("host", url.toString().trimEnd('/')).apply()
    }

    fun connect(token: String, username: String = "") {
        vault.save(token.trim())
        settings.edit().putString("username", username.trim().take(120)).apply()
    }

    fun disconnect() { vault.clear(); settings.edit().remove("username").apply() }

    private fun request(path: String, method: String = "GET", body: JSONObject? = null): Any {
        val connection = URL(host() + "/api/v4" + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 30000; connection.requestMethod = method
        connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("User-Agent", "M4-Cursor/2.0")
        vault.read()?.let { connection.setRequestProperty("PRIVATE-TOKEN", it) }
        try {
            if (body != null) {
                connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes().also { data -> require(data.size <= 2_000_000) } } ?: ByteArray(0)
            val text = String(bytes, Charsets.UTF_8)
            if (code !in 200..299) error("GitLab HTTP $code: " + runCatching { JSONObject(text).optString("message") }.getOrDefault(text).take(300))
            return if (text.isBlank()) JSONObject().put("status_code", code) else JSONTokener(text).nextValue()
        } finally { connection.disconnect() }
    }

    fun call(action: String, args: JSONObject, owner: String): JSONObject {
        if (action == "status") return status().put("status", "observed")
        if (action == "connect") { connect(args.getString("token"), args.optString("username")); return status().put("status", "connected") }
        if (action == "disconnect") { disconnect(); return status().put("status", "disconnected") }
        val project = args.optString("project").takeIf { it.isNotBlank() }
        val encoded = project?.let { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        val writes = setOf("create_issue", "create_merge_request", "trigger_pipeline")
        if (action in writes) {
            require(vault.read() != null) { "اربط GitLab برمز وصول أولاً" }
            if (!CapabilityBroker.request(owner, "GitLab: $action", args)) return JSONObject().put("status", "denied")
        }
        val data: Any = when (action) {
            "projects" -> request("/projects?membership=true&per_page=50&order_by=last_activity_at")
            "project" -> request("/projects/$encoded")
            "issues" -> request("/projects/$encoded/issues?state=all&per_page=50")
            "merge_requests" -> request("/projects/$encoded/merge_requests?state=all&per_page=50")
            "pipelines" -> request("/projects/$encoded/pipelines?per_page=50")
            "pipeline" -> request("/projects/$encoded/pipelines/${args.getLong("id")}")
            "create_issue" -> request("/projects/$encoded/issues", "POST", JSONObject().put("title", args.getString("title").take(250)).put("description", args.optString("description").take(20000)))
            "create_merge_request" -> request("/projects/$encoded/merge_requests", "POST", JSONObject().put("source_branch", args.getString("source_branch")).put("target_branch", args.optString("target_branch", "main")).put("title", args.getString("title").take(250)).put("description", args.optString("description").take(20000)))
            "trigger_pipeline" -> request("/projects/$encoded/pipeline", "POST", JSONObject().put("ref", args.getString("ref")))
            else -> error("GitLab action not registered")
        }
        return JSONObject().put("status", if (action in writes) "published" else "observed").put("data", data)
    }
}
