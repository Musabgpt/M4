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
        .put("credential_status", "not_revalidated").put("last_validated_ms", settings.getLong("last_validated", 0))
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

    /**
     * NewAl-style token sign-in: verify the GitHub account BEFORE saving
     * credentials; never send the token to the LLM or persist it in plaintext.
     */
    fun connectToken(tokenInput: String): JSONObject {
        val token = tokenInput.trim()
        require(token.length in 20..2000 && token.none { it.isWhitespace() }) {
            "GitHub token must be a non-empty personal access token"
        }
        val account = exchange("https://api.github.com/user", "GET", token = token) as JSONObject
        val login = account.getString("login")
        require(login.matches(Regex("[A-Za-z0-9-]{1,39}")))
        vault.save(token)
        settings.edit().putString("login", login)
            .putLong("last_validated", System.currentTimeMillis()).apply()
        return status().put("credential_status", "verified").put("status", "verified")
    }

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
                vault.save(token); settings.edit().putString("login", account.getString("login")).putLong("last_validated", System.currentTimeMillis()).apply()
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
        if (action == "update_install") return ApkUpdates.install(context, owner, args.getString("update_id"))
        if (action == "update_status") return ApkUpdates.status(context, owner, args.getString("update_id"))
        if (action == "check_connection") {
            if (vault.read() == null) return status().put("status", "permission_required").put("error", "الحساب غير متصل")
            val user = api("/user") as JSONObject
            settings.edit().putString("login", user.getString("login")).putLong("last_validated", System.currentTimeMillis()).apply()
            return status().put("credential_status", "verified").put("status", "verified")
        }
        val writes = setOf("pr_create", "issue_create", "workflow_dispatch", "snapshot_push")
        if (action in writes) {
            if (vault.read() == null) return JSONObject().put("status", "permission_required").put("needs", "github_oauth").put("error", "اربط GitHub من لوحة الحساب أولاً")
            if (!CapabilityBroker.request(owner, "GitHub: $action", args)) return JSONObject().put("status", "denied").put("error", "رفض المستخدم إجراء GitHub")
        }
        val repository = if (action == "repositories") "" else repo(args)
        if (action == "job_logs") {
            val file = download("/repos/$repository/actions/jobs/${args.getLong("id")}/logs", 3_000_000)
            try {
                val text = file.readText()
                val report = com.musabgpt.m4.artifacts.ArtifactRepository.saveReport(context, owner, "github-job-${args.getLong("id")}.md", "# GitHub job logs\n\nSource: https://github.com/$repository/actions\n\n```text\n${text.take(190000)}\n```\n")
                return report.json().put("published", true).put("status", "observed").put("text", text.take(30000)).put("truncated", text.length > 30000)
            } finally { file.delete() }
        }
        if (action == "artifact_download") {
            val metadata = api("/repos/$repository/actions/artifacts/${args.getLong("id")}") as JSONObject
            require(!metadata.optBoolean("expired")) { "مرفق CI منتهي الصلاحية" }
            val archive = download("/repos/$repository/actions/artifacts/${args.getLong("id")}/zip", 200_000_000)
            return ApkUpdates.stage(context, archive, owner, JSONObject().put("repository", repository).put("artifact_id", metadata.getLong("id")).put("run", metadata.optJSONObject("workflow_run")))
        }
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

    private fun download(path: String, maximum: Long): java.io.File {
        val directory = java.io.File(context.cacheDir, "github-downloads").apply { mkdirs() }
        directory.listFiles().orEmpty().filter { System.currentTimeMillis() - it.lastModified() > 3600000 }.forEach { it.delete() }
        val target = java.io.File(directory, java.util.UUID.randomUUID().toString())
        var url = URL("https://api.github.com$path")
        val deadline = android.os.SystemClock.elapsedRealtime() + 120000
        try {
            repeat(4) { step ->
                val host = url.host.lowercase()
                require(url.protocol == "https" && url.userInfo == null && (host == "api.github.com" || host.endsWith(".githubusercontent.com") || host.endsWith(".blob.core.windows.net"))) { "GitHub redirected outside official artifact hosts" }
                val connection = url.openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false; connection.connectTimeout = 15000; connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "M4-NewAl/2.0")
                if (step == 0) vault.read()?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                try {
                    val code = connection.responseCode
                    if (code in setOf(301,302,303,307,308)) { url = URL(url, connection.getHeaderField("Location") ?: error("Missing GitHub redirect")) }
                    else {
                        require(code == 200) { "GitHub download HTTP $code" }
                        var count = 0L
                        connection.inputStream.use { input -> target.outputStream().use { output -> val bytes = ByteArray(16384); while (true) { require(android.os.SystemClock.elapsedRealtime() < deadline) { "GitHub download deadline exceeded" }; val n = input.read(bytes); if (n < 0) break; count += n; require(count <= maximum) { "GitHub download exceeded limit" }; output.write(bytes,0,n) } } }
                        require(count > 0); return target
                    }
                } finally { connection.disconnect() }
            }
            error("GitHub redirect limit exceeded")
        } catch (e: Exception) { target.delete(); throw e }
    }

    private fun pushSnapshot(repository: String, args: JSONObject): JSONObject {
        val name = branch(args.getString("branch")); val files = args.getJSONArray("files")
        require(files.length() in 1..80)
        val repositoryInfo = api("/repos/$repository") as JSONObject
        val default = repositoryInfo.getString("default_branch")
        val receiptKey = "push:$repository:$name"
        val previous = settings.getString(receiptKey, null)?.let { JSONObject(it) }
        val baseRef = api("/repos/$repository/git/ref/heads/" + if (previous == null) default else name) as JSONObject
        val parent = baseRef.getJSONObject("object").getString("sha")
        if (previous != null) require(parent == previous.getString("commit")) { "Remote branch changed since the last M4 push; refresh and review before publishing" }
        val baseline = previous?.getString("baseline") ?: parent
        val commit = api("/repos/$repository/git/commits/$parent") as JSONObject
        val elements = JSONArray()
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i); val path = file.getString("path")
            require(!path.startsWith('/') && path.split('/').none { it in setOf("..", ".git", ".term-agent") } && !path.contains('\\') && !path.contains('\u0000'))
            require(!path.startsWith(".github/") && path !in setOf("app/src/main/AndroidManifest.xml", "android/signing-cert-sha256.txt", "scripts/build-signed-release.sh", "android/PROTECTED_PATHS")) { "Protected changes require human security review" }
            val expected = file.optString("base_sha", "")
            val encodedPath = path.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val current = try { api("/repos/$repository/contents/$encodedPath?ref=$baseline") as JSONObject } catch (e: IllegalStateException) { if (e.message?.contains("HTTP 404") == true) null else throw e }
            require((current?.optString("sha") ?: "") == expected) { "Remote file changed since the local baseline: $path" }
            val entry = JSONObject().put("path", path).put("mode", file.optString("mode", "100644")).put("type", "blob")
            require(entry.getString("mode") in setOf("100644", "100755"))
            if (file.optBoolean("deleted")) entry.put("sha", JSONObject.NULL)
            else entry.put("content", file.getString("content").also { require(it.length <= 500000) })
            elements.put(entry)
        }
        val tree = api("/repos/$repository/git/trees", "POST", JSONObject().put("base_tree", commit.getJSONObject("tree").getString("sha")).put("tree", elements)) as JSONObject
        val uploaded = api("/repos/$repository/git/commits", "POST", JSONObject().put("message", args.getString("message").take(1000)).put("tree", tree.getString("sha")).put("parents", JSONArray().put(parent))) as JSONObject
        if (previous == null) api("/repos/$repository/git/refs", "POST", JSONObject().put("ref", "refs/heads/$name").put("sha", uploaded.getString("sha")))
        else api("/repos/$repository/git/refs/heads/$name", "PATCH", JSONObject().put("sha", uploaded.getString("sha")).put("force", false))
        settings.edit().putString(receiptKey, JSONObject().put("commit", uploaded.getString("sha")).put("baseline", baseline).toString()).apply()
        return JSONObject().put("commit", uploaded.getString("sha")).put("branch", name).put("url", "https://github.com/$repository/tree/$name").put("force", false)
    }
}
