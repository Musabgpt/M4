package com.musabgpt.m4.terminal

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Setup and connection receipts only: the Agent owns commands and interactive sessions. */
@Composable
fun TermuxPanel(owner: String, autoSetup: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(JSONObject()) }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loginSession by remember(owner) { mutableStateOf<String?>(null) }
    var cursor by remember(owner) { mutableStateOf(0) }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (failure: Exception) { output = failure.message.orEmpty() }
            finally { busy = false }
        }
    }
    suspend fun refresh() { status = withContext(Dispatchers.IO) { TermuxExecutionBridge.status(context, owner) } }
    fun setup() = action {
        val started = withContext(Dispatchers.IO) { TermuxExecutionBridge.setup(context, owner) }
        output = started.optString("message", started.optString("error", started.optString("status")))
        if (started.optString("status") == "starting") {
            var attempts = 0
            do {
                delay(2000); refresh(); attempts++
            } while (status.optString("status") == "unavailable" && attempts < 300)
        } else refresh()
        if (status.optString("status") == "failed") output = status.optJSONObject("bootstrap")?.toString(2).orEmpty()
    }
    LaunchedEffect(owner) { refresh(); if (autoSetup) setup() }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Termux Execution Engine", style = MaterialTheme.typography.titleLarge)
        Text("الوكيل ينفّذ الأوامر ويقرأ نتائجها ويعدّل الملفات من المحادثة. لا تحتاج إلى نسخ الأوامر أو فتح نافذة طرفية لكل خطوة.")
        Text("الحالة: ${status.optString("status", "جارٍ الفحص")}")
        Text("مجلد المشاريع: ${status.optString("root", TermuxExecutionBridge.ROOT)}", style = MaterialTheme.typography.bodySmall)
        if (!TermuxClient.status(context).optBoolean("installed")) Button(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
        }) { Text("تثبيت Termux") }
        Text("يلزم منح RUN_COMMAND وتفعيل allow-external-apps في Termux مرة واحدة. تثبيت الأدوات وخادم الملفات يتم تلقائيًا بعد التفعيل.", style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = { setup() }) { Text(if (status.optBoolean("authorized")) "إعادة فحص المحرك" else "تفعيل الوكيل لهذه المحادثة") }
        TextButton(enabled = !busy, onClick = { action { refresh() } }) { Text("تحديث الحالة") }
        HorizontalDivider()
        Text("GitHub · Git + gh", style = MaterialTheme.typography.titleMedium)
        Button(enabled = !busy && status.optBoolean("authorized"), onClick = { action {
            val row = withContext(Dispatchers.IO) { TermuxExecutionBridge.call(context, owner, "exec_command",
                JSONObject().put("command", "gh auth login --hostname github.com --git-protocol https --web").put("tty", true).put("yield_time_ms", 1000)) }
            loginSession = row.optString("session_id").takeIf { it.isNotBlank() }; cursor = row.optInt("next_cursor")
            output = row.optString("stdout") + row.optString("stderr")
        } }) { Text("ربط GitHub") }
        if (loginSession != null) {
            TextButton(enabled = !busy, onClick = { action {
                val row = withContext(Dispatchers.IO) { TermuxExecutionBridge.call(context, owner, "write_stdin",
                    JSONObject().put("session_id", loginSession).put("chars", "\n").put("cursor", cursor).put("yield_time_ms", 1000)) }
                cursor = row.optInt("next_cursor", cursor); output += row.optString("stdout") + row.optString("stderr")
            } }) { Text("متابعة تسجيل الدخول") }
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device"))) }) { Text("فتح صفحة التفويض") }
        }
        TextButton(enabled = !busy && status.optBoolean("authorized"), onClick = { action {
            val row = withContext(Dispatchers.IO) { TermuxExecutionBridge.call(context, owner, "exec_command",
                JSONObject().put("command", "gh auth status --hostname github.com && gh auth setup-git").put("yield_time_ms", 1000)) }
            output = row.optString("stdout") + row.optString("stderr")
            if (row.isNull("exit_code")) output += "\nما زال التحقق قيد التنفيذ: ${row.optString("session_id")}"
        } }) { Text("التحقق من الحساب وربط Git") }
        TextButton(enabled = !busy, onClick = { action {
            withContext(Dispatchers.IO) { TermuxExecutionBridge.call(context, owner, "close_owner", JSONObject()) }
            loginSession = null; output = "أُلغي التفويض وأُوقفت جلسات هذه المحادثة"; refresh()
        } }) { Text("إيقاف الجلسات وإلغاء التفويض") }
        if (output.isNotBlank()) Text(output.takeLast(16000), style = MaterialTheme.typography.bodySmall)
    }
}
