package com.musabgpt.m4.terminal

import android.content.Context
import android.util.Base64
import com.musabgpt.m4.runtime.CapabilityBroker
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.UUID

/** Authenticated RPC to a Termux-owned process, bootstrapped through RUN_COMMAND. */
object TermuxExecutionBridge {
    const val ROOT = "/data/data/com.termux/files/home/projects"
    private const val HOME = "/data/data/com.termux/files/home"
    private val grants = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun configFile(context: Context) = File(context.filesDir, "termux-engine.json")
    private fun config(context: Context) = runCatching { JSONObject(configFile(context).readText()) }.getOrNull()
    private val readTools = setOf("read_file", "read_text_file", "read_multiple_files", "list_directory", "list_directory_with_sizes", "directory_tree", "search_files", "get_file_info", "list_allowed_directories")
    fun readOnly(action: String, args: JSONObject) = action in setOf("status", "read_output", "filesystem_tools") ||
        (action == "filesystem_call" && args.optString("name") in readTools) || (action == "github_api" && args.optString("method", "GET") == "GET")

    fun status(context: Context, owner: String): JSONObject {
        val settings = config(context) ?: return TermuxClient.status(context).put("status", "not_configured")
        return try { rpc(settings, owner, "status", JSONObject(), 1000).put("authorized", owner in grants) }
        catch (_: Exception) {
            val id = settings.optString("bootstrap_id")
            val receipt = if (id.isNotBlank()) runCatching { TermuxClient.output(context, settings.optString("bootstrap_owner"), id) }.getOrNull() else null
            JSONObject().put("status", if (receipt?.optString("status") in setOf("failed", "blocked")) "failed" else if (receipt?.optString("status") == "pending_result" && System.currentTimeMillis() - settings.optLong("started_at") < 900000) "starting" else "unavailable")
                .put("bootstrap", receipt ?: JSONObject.NULL).put("error", "محرك Termux غير جاهز أو أوقفه أندرويد؛ استخدم تفعيل المحرك")
        }
    }

    @Synchronized
    fun setup(context: Context, owner: String): JSONObject {
        if (!TermuxClient.status(context).optBoolean("installed")) return JSONObject().put("status", "unavailable").put("error", "ثبّت Termux أولًا")
        val consent = JSONObject().put("workspace", ROOT).put("tools", "python, git, gh, nodejs-lts, Filesystem MCP")
            .put("scope", "تفويض الوكيل لهذه المحادثة: تنفيذ أوامر وتعديل ملفات مشاريع Termux. أوامر Shell تملك صلاحيات Termux؛ ليست محصورة أمنيًا بمجلد المشاريع. يمكن إلغاء التفويض وإيقاف الجلسات من صفحة الإضافة.")
        if (!CapabilityBroker.request(owner, "تفعيل محرك Termux للوكيل", consent)) return JSONObject().put("status", "denied")
        grants.add(owner)
        if (status(context, owner).optString("status") == "ready") return status(context, owner)
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val settings = JSONObject().put("port", port).put("token", UUID.randomUUID().toString() + UUID.randomUUID())
            .put("root", ROOT).put("mcp_command", JSONArray().put("node").put("$HOME/.m4-engine/tools/node_modules/@modelcontextprotocol/server-filesystem/dist/index.js"))
        fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val source = context.assets.open("termux_engine.py").use { encode(it.readBytes()) }
        val encodedConfig = encode(settings.toString().toByteArray())
        // Only fixed paths and base64 are interpolated; model input is never shell code here.
        val command = "set -e; umask 077; pkg install -y python git gh nodejs-lts; " +
            "mkdir -p '$HOME/.m4-engine' '$ROOT'; " +
            "printf '%s' '$source' | base64 -d > '$HOME/.m4-engine/engine.py'; " +
            "printf '%s' '$encodedConfig' | base64 -d > '$HOME/.m4-engine/config.json'; " +
            "npm install --prefix '$HOME/.m4-engine/tools' --no-audit --no-fund @modelcontextprotocol/server-filesystem@1.0.0; " +
            "exec python '$HOME/.m4-engine/engine.py' --config '$HOME/.m4-engine/config.json'"
        val result = TermuxClient.runApproved(context, JSONObject().put("executable", "bash")
            .put("arguments", JSONArray().put("-lc").put(command)).put("cwd", HOME).put("timeout", 1), owner)
        if (result.optString("command_id").isBlank()) { grants.remove(owner); return result }
        settings.put("started_at", System.currentTimeMillis()).put("bootstrap_id", result.getString("command_id")).put("bootstrap_owner", owner)
        val temp = File(context.filesDir, ".termux-engine.tmp"); temp.writeText(settings.toString()); check(temp.renameTo(configFile(context)))
        return JSONObject().put("status", "starting").put("command_id", result.getString("command_id"))
            .put("message", "بدأ تجهيز Termux؛ تحقق عبر TermuxStatus قبل التنفيذ. الإرسال وحده ليس نجاحًا.")
    }

    fun call(context: Context, owner: String, action: String, args: JSONObject, mode: String = "auto_project"): JSONObject {
        if (action == "setup") return setup(context, owner)
        if (action == "status") return status(context, owner)
        if (action == "close_owner") {
            grants.remove(owner)
            return config(context)?.let { rpc(it, owner, action, args) } ?: JSONObject().put("status", "closed")
        }
        require(mode != "plan_only" || readOnly(action, args)) { "وضع التخطيط لا ينفّذ أو يعدّل" }
        if (!readOnly(action, args)) {
            require(owner in grants) { "فعّل محرك Termux لهذه المحادثة أولًا" }
            val dangerous = Regex("(?i)(\\brm\\s+[^\\n]*-[^\\s]*[rf]|\\bgit\\s+reset\\s+--hard|\\bmkfs\\b|\\bdd\\b|--force(?:\\s|$)|\\bgh\\s+(?:repo\\s+delete|secret)\\b)").containsMatchIn(args.toString()) || (action == "github_api" && args.optString("method") == "DELETE")
            if ((mode != "auto_project" || dangerous) && !CapabilityBroker.request(owner, "إجراء Termux يحتاج مراجعة", JSONObject().put("action", action).put("arguments", args)))
                return JSONObject().put("status", "denied")
        }
        return rpc(config(context) ?: error("محرك Termux غير مهيأ"), owner, action, args)
    }

    private fun rpc(settings: JSONObject, owner: String, action: String, args: JSONObject, timeout: Int = 45000): JSONObject {
        val connection = URL("http://127.0.0.1:${settings.getInt("port")}/rpc").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = timeout.coerceAtMost(3000); connection.readTimeout = timeout
            connection.instanceFollowRedirects = false; connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + settings.getString("token"))
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(JSONObject().put("owner", owner).put("action", action).put("arguments", args).toString().toByteArray()) }
            check(connection.responseCode == 200) { "Termux bridge HTTP ${connection.responseCode}" }
            val body = connection.inputStream.use { input ->
                val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    check(bytes.size() + count <= 1000000) { "Termux response too large" }; bytes.write(buffer, 0, count) }
                bytes.toString("UTF-8")
            }
            val response = JSONObject(body)
            check(response.optBoolean("ok")) { response.optString("error", "Termux request failed") }
            return response.getJSONObject("result")
        } finally { connection.disconnect() }
    }
}
