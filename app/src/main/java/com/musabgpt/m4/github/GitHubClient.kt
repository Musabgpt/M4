package com.musabgpt.m4.github

import android.content.Context
import com.musabgpt.m4.runtime.CapabilityBroker
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL

/** Credentials are used only in this native client, never returned to a model. */
class GitHubClient(private val context: Context) {
    private val vault = CredentialVault(context)
    private val settings = context.getSharedPreferences("m4_github", 0)
    fun status() = JSONObject().put("connected", vault.read() != null).put("login", settings.getString("login", ""))
        .put("client_configured", !settings.getString("client_id", "").isNullOrBlank())
    fun setClientId(id: String) { require(id.matches(Regex("[A-Za-z0-9_.-]{6,120}"))); settings.edit().putString("client_id", id).apply() }
    fun disconnect() { vault.clear(); settings.edit().remove("login").apply() }

    private fun exchange(url: String, method: String, body: JSONObject? = null, token: String? = null): Any {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
        connection.requestMethod = method; connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "M4-NewAl/2.0"); connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            val output = java.io.ByteArrayOutputStream()
            stream?.use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 1500000) { "GitHub response too large" }; output.write(buffer, 0, n) } }
            val text = output.toString("UTF-8")
            if (code !in 200..299) error("GitHub HTTP $code: " + runCatching { JSONObject(text).optString("message") }.getOrDefault("Request failed").take(200))
            return if (text.isBlank()) JSONObject().put("status_code", code) else JSONTokener(text).nextValue()
        } finally { connection.disconnect() }
    }
    private fun api(path: String, method: String = "GET", body: JSONObject? = null): Any = exchange("https://api.github.com$path", method, body, vault.read())
    private fun repo(args: JSONObject): String = args.getString("repository").also { require(it.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) }
    private fun branch(value: String): String = value.also { require(it.matches(Regex("(?:m4|newal|codex)/[A-Za-z0-9][A-Za-z0-9._/-]{0,100}")) && !it.contains("..") && !it.endsWith('/')) }

    fun beginDeviceFlow(): JSONObject {
        val client = settings.getString("client_id", "").orEmpty()
        require(client.isNotBlank()) { "يلزم client_id لتطبيق OAuth الخاص بـ M4؛ اتصال Codex لا ينقل اعتماده إلى الهاتف" }
        return exchange("https://github.com/login/device/code", "POST", JSONObject().put("client_id", client).put("scope", "repo")) as JSONObject
    }
    fun finishDeviceFlow(device: JSONObject, stopped: () -> Boolean): JSONObject {
        val end = System.nanoTime() + device.getInt("expires_in").coerceIn(1, 1800) * 1_000_000_000L
        var interval = device.optInt("interval", 5).coerceIn(5, 60)
        while (!stopped() && System.nanoTime() < end) {
            for (i in 0 until interval * 4) { if (stopped()) return JSONObject().put("status", "canceled"); Thread.sleep(250) }
            val result = exchange("https://github.com/login/oauth/access_token", "POST", JSONObject()
                .put("client_id", settings.getString("client_id", "")).put("device_code", device.getString("device_code"))
                .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")) as JSONObject
            if (result.has("access_token")) {
                val token = result.getString("access_token")
                val account = exchange("https://api.github.com/user", "GET", token = token) as JSONObject
                vault.save(token); settings.edit().putString("login", account.getString("login")).apply()
                return status().put("status", "connected")
            }
            when (result.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> interval = (interval + 5).coerceAtMost(120)
                else -> return JSONObject().put("status", "failed").put("error", result.optString("error_description", result.optString("error")))
            }
        }
        return JSONObject().put("status", if (stopped()) "canceled" else "expired").put("error", "انتهى تفويض GitHub دون اتصال")
    }

    fun call(action: String, args: JSONObject, owner: String): JSONObject {
        if (action == "status") return status().put("status", "observed")
        val writes = setOf("pr_create", "issue_create", "workflow_dispatch", "snapshot_push")
        if (action in writes) {
            if (vault.read() == null) return JSONObject().put("status", "permission_required").put("needs", "github_oauth").put("error", "اربط GitHub من لوحة الحساب أولاً")
            if (!CapabilityBroker.request(owner, "GitHub: $action", args)) return JSONObject().put("status", "denied").put("error", "رفض المستخدم إجراء GitHub")
        }
        val repository = if (action == "repositories") "" else repo(args)
        val data: Any = when (action) {
            "repositories" -> if (vault.read() != null) api("/user/repos?per_page=50&sort=updated") else {
                val account = args.getString("owner"); require(account.matches(Regex("[A-Za-z0-9-]{1,39}"))); api("/users/$account/repos?per_page=50&sort=updated")
            }
            "runs" -> api("/repos/$repository/actions/runs?per_page=20")
            "run" -> api("/repos/$repository/actions/runs/${args.getLong("id")}")
            "jobs" -> api("/repos/$repository/actions/runs/${args.getLong("id")}/jobs?per_page=50")
            "artifacts" -> api("/repos/$repository/actions/runs/${args.getLong("id")}/artifacts?per_page=50")
            "pull_requests" -> api("/repos/$repository/pulls?per_page=30")
            "pull_request" -> api("/repos/$repository/pulls/${args.getLong("id")}")
            "pr_create" -> {
                val head = branch(args.getString("head")); val base = args.optString("base", "main"); require(base.matches(Regex("[A-Za-z0-9._/-]{1,100}")))
                api("/repos/$repository/pulls", "POST", JSONObject().put("head", head).put("base", base).put("title", args.getString("title").take(250)).put("body", args.optString("body").take(20000)).put("draft", true))
            }
            "issue_create" -> api("/repos/$repository/issues", "POST", JSONObject().put("title", args.getString("title").take(250)).put("body", args.optString("body").take(20000)))
            "workflow_dispatch" -> {
                val workflow = args.getString("workflow"); require(workflow.matches(Regex("[A-Za-z0-9_.-]{1,100}")))
                api("/repos/$repository/actions/workflows/$workflow/dispatches", "POST", JSONObject().put("ref", branch(args.getString("ref"))).put("inputs", args.optJSONObject("inputs") ?: JSONObject()))
            }
            "snapshot_push" -> pushSnapshot(repository, args)
            else -> error("GitHub action not registered")
        }
        return JSONObject().put("status", if (action in writes) "published" else "observed").put("data", data)
    }

    private fun pushSnapshot(repository: String, args: JSONObject): JSONObject {
        val name = branch(args.getString("branch")); val files = args.getJSONArray("files")
        require(files.length() in 1..80)
        val repositoryInfo = api("/repos/$repository") as JSONObject
        val default = repositoryInfo.getString("default_branch")
        val baseRef = api("/repos/$repository/git/ref/heads/$default") as JSONObject
        val parent = baseRef.getJSONObject("object").getString("sha")
        val commit = api("/repos/$repository/git/commits/$parent") as JSONObject
        val elements = JSONArray()
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i); val path = file.getString("path")
            require(!path.startsWith('/') && path.split('/').none { it in setOf("..", ".git", ".term-agent") } && !path.contains('\\') && !path.contains('\u0000'))
            require(!path.startsWith(".github/") && path !in setOf("app/src/main/AndroidManifest.xml", "android/signing-cert-sha256.txt", "scripts/build-signed-release.sh", "android/PROTECTED_PATHS")) { "Protected changes require human security review" }
            val expected = file.optString("base_sha", "")
            val encodedPath = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val current = try { api("/repos/$repository/contents/$encodedPath?ref=$default") as JSONObject } catch (e: IllegalStateException) { if (e.message?.contains("HTTP 404") == true) null else throw e }
            require((current?.optString("sha") ?: "") == expected) { "Remote file changed since the local baseline: $path" }
            val entry = JSONObject().put("path", path).put("mode", file.optString("mode", "100644")).put("type", "blob")
            require(entry.getString("mode") in setOf("100644", "100755"))
            if (file.optBoolean("deleted")) entry.put("sha", JSONObject.NULL)
            else entry.put("content", file.getString("content").also { require(it.length <= 500000) })
            elements.put(entry)
        }
        val tree = api("/repos/$repository/git/trees", "POST", JSONObject().put("base_tree", commit.getJSONObject("tree").getString("sha")).put("tree", elements)) as JSONObject
        val uploaded = api("/repos/$repository/git/commits", "POST", JSONObject().put("message", args.getString("message").take(1000)).put("tree", tree.getString("sha")).put("parents", JSONArray().put(parent))) as JSONObject
        api("/repos/$repository/git/refs", "POST", JSONObject().put("ref", "refs/heads/$name").put("sha", uploaded.getString("sha")))
        return JSONObject().put("commit", uploaded.getString("sha")).put("branch", name).put("url", "https://github.com/$repository/tree/$name").put("force", false)
    }
}
