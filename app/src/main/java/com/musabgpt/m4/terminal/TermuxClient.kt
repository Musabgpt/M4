package com.musabgpt.m4.terminal

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.musabgpt.m4.runtime.CapabilityBroker
import com.musabgpt.m4.runtime.HostPermissionRequest
import com.musabgpt.m4.artifacts.ArtifactRepository
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Official RUN_COMMAND service + result PendingIntent, with durable receipts. */
object TermuxClient {
    private const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    private fun directory(context: Context) = File(context.filesDir, "termux-results").apply { mkdirs() }
    fun status(context: Context): JSONObject {
        val installed = runCatching { context.packageManager.getApplicationInfo("com.termux", 0) }.isSuccess
        return JSONObject().put("installed", installed).put("permission_granted", context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED)
            .put("external_apps", "unknown_until_command_result").put("backend", "termux")
    }
    fun run(context: Context, args: JSONObject, owner: String): JSONObject =
        execute(context, args, owner, preapproved = false)

    /**
     * A previously approved, immutable scheduled command may run in background
     * without an interactive dialog. Verify ownership, command and enabled
     * state directly at the authorization boundary.
     */
    fun runApprovedTask(context: Context, task: com.musabgpt.m4.scheduler.ScheduledTask): JSONObject {
        val current = com.musabgpt.m4.scheduler.TaskDatabase.get(context).find(task.id)
            ?: return JSONObject().put("status", "denied").put("error", "المهمة لم تعد موجودة")
        require(current.id == task.id && current.owner == task.owner &&
            current.command == task.command && current.enabled) {
            "فشل التحقق من صلاحية الأمر المجدول"
        }
        val request = JSONObject().put("executable", "bash")
            .put("arguments", org.json.JSONArray(listOf("-lc", task.command)))
            .put("timeout", 90)
        return execute(context, request, task.owner, preapproved = true)
    }

    private fun execute(context: Context, args: JSONObject, owner: String, preapproved: Boolean): JSONObject {
        if (!status(context).getBoolean("installed")) return JSONObject().put("status", "unavailable").put("error", "Termux غير مثبت؛ لا يوجد تشغيل بديل عبر الطرفية العربية.")
        val executable = args.getString("executable")
        require(executable.matches(Regex("[a-zA-Z][a-zA-Z0-9_.-]{0,80}"))) { "اسم برنامج Termux غير صالح" }
        val arguments = args.optJSONArray("arguments") ?: org.json.JSONArray()
        require(arguments.length() <= 50 && arguments.toString().length <= 12000)
        val cwd = args.optString("cwd", "/data/data/com.termux/files/home")
        require(cwd.startsWith("/data/data/com.termux/files/") && !cwd.split('/').contains(".."))
        if (!preapproved && !CapabilityBroker.request(owner, "تشغيل داخل Termux", args))
            return JSONObject().put("status", "denied").put("error", "رفض المستخدم أمر Termux")
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            if (preapproved || !HostPermissionRequest.request(PERMISSION))
                return JSONObject().put("status", "permission_required").put("needs", PERMISSION)
                    .put("error", "يلزم منح صلاحية Termux RUN_COMMAND من إعدادات Android")
        }
        val id = UUID.randomUUID().toString(); val nonce = UUID.randomUUID().toString()
        val folder = File(directory(context), id).apply { mkdirs() }
        File(folder, "request.json").writeText(JSONObject().put("owner", owner).put("nonce", nonce).put("command", args).toString())
        val callback = Intent(context, TermuxResultReceiver::class.java).setData(Uri.parse("m4-termux://result/$id/$nonce"))
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), callback, PendingIntent.FLAG_ONE_SHOT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
        val command = Intent("com.termux.RUN_COMMAND").setClassName("com.termux", "com.termux.app.RunCommandService")
            .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/$executable")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", Array(arguments.length()) { arguments.getString(it) })
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", cwd).putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
        try { context.startService(command) }
        catch (e: Exception) { pending.cancel(); return JSONObject().put("status", "blocked").put("error", "Termux لم يقبل الأمر: ${e.message}") }
        val end = System.nanoTime() + args.optInt("timeout", 60).coerceIn(1, 120) * 1_000_000_000L
        val response = File(folder, "result.json")
        while (!response.isFile && System.nanoTime() < end) Thread.sleep(100)
        if (!response.isFile) return JSONObject().put("status", "pending_result").put("command_id", id).put("backend", "termux")
            .put("error", "أُرسل الأمر دون نتيجة حتى الآن؛ قد يلزم allow-external-apps=true داخل Termux. راجع TermuxOutput؛ لا يُعد الإرسال نجاحًا")
        return JSONObject(response.readText())
    }
    fun output(context: Context, owner: String, id: String): JSONObject {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        val folder = File(directory(context), id); val request = JSONObject(File(folder, "request.json").readText())
        require(request.getString("owner") == owner)
        val file = File(folder, "result.json")
        return if (file.isFile) JSONObject(file.readText()) else JSONObject().put("status", "pending_result").put("command_id", id)
    }
    fun receive(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments ?: return
        if (segments.size != 2 || !segments[0].matches(Regex("[a-f0-9-]{36}"))) return
        val folder = File(directory(context), segments[0]); val request = runCatching { JSONObject(File(folder, "request.json").readText()) }.getOrNull() ?: return
        if (request.getString("nonce") != segments[1] || File(folder, "result.json").exists()) return
        val bundle = intent.getBundleExtra("result") ?: return
        // Official Termux service returns Activity.RESULT_OK (-1) when there
        // are no internal errors. Zero is NOT a successful return code here.
        val error = bundle.getInt("err", Int.MIN_VALUE)
        val hasExit = bundle.containsKey("exitCode")
        val row = JSONObject().put("backend", "termux").put("command_id", segments[0])
            .put("exit_code", if (hasExit) bundle.getInt("exitCode") else JSONObject.NULL)
            .put("stdout", bundle.getString("stdout", "").take(64000)).put("stderr", bundle.getString("stderr", "").take(64000))
            .put("status", if (error != android.app.Activity.RESULT_OK || !hasExit) "blocked"
                else if (bundle.getInt("exitCode") == 0) "succeeded" else "failed")
            .put("truncated", bundle.getInt("stdout_original_length", 0) > 64000 || bundle.getInt("stderr_original_length", 0) > 64000)
        if (error != android.app.Activity.RESULT_OK) row.put("error", bundle.getString("errmsg", "Termux command rejected").take(1000))
        val artifact = ArtifactRepository.saveReport(context, request.getString("owner"), "termux-${segments[0]}.md", "# Termux command receipt\n\n```json\n${row.toString(2)}\n```\n")
        row.put("report_artifact", artifact.json())
        val temporary = File(folder, ".result.tmp"); temporary.writeText(row.toString()); require(temporary.renameTo(File(folder, "result.json")))
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { runCatching { TermuxClient.receive(context, intent) } }
}
