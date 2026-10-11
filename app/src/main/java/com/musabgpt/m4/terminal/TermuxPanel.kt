package com.musabgpt.m4.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Only real Termux executions and results are shown, no JSON transport dumps. */
@Composable
fun TermuxPanel(owner: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("python --version") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var meta by remember { mutableStateOf(TermuxClient.status(context)) }
    fun describe(row: JSONObject): String = when (row.optString("status")) {
        "succeeded" -> {
            val output = (row.optString("stdout") + "\n" + row.optString("stderr")).trim()
            if (output.isEmpty()) "نجح التنفيذ، ولم يُرجع الأمر نصاً." else output.take(6000)
        }
        "permission_required" -> "امنح M4 صلاحية RUN_COMMAND من إعدادات Android."
        "denied" -> "ألغيت العملية؛ لم يُنفذ الأمر."
        "blocked" -> "رفض Termux تنفيذ الأمر. تحقق من السماح بالتطبيقات الخارجية."
        "pending_result" -> "الأمر قيد التنفيذ؛ لم تصل نتيجة مؤكدة بعد."
        else -> "تعذر تنفيذ الأمر داخل Termux. يمكنك تجربة أمر تشخيص أقصر."
    }
    suspend fun execute(args: JSONObject): JSONObject {
        val first = withContext(Dispatchers.IO) { TermuxClient.run(context, args, owner) }
        if (first.optString("status") != "pending_result") return first
        val id = first.optString("command_id")
        if (id.isBlank()) return first
        return withTimeoutOrNull(60000L) {
            var latest: JSONObject
            do {
                delay(350)
                latest = withContext(Dispatchers.IO) { TermuxClient.output(context, owner, id) }
            } while (latest.optString("status") == "pending_result")
            latest
        } ?: first
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Termux — محرك M4", style = MaterialTheme.typography.titleLarge)
        Text(
            "الحالة: " + if (meta.optBoolean("installed")) "Termux مثبت" else "Termux غير مثبت",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            "صلاحية تنفيذ الأوامر: " +
                if (meta.optBoolean("permission_granted")) "ممنوحة" else "غير ممنوحة"
        )
        Text("البرامج تعمل في Termux المثبت على الهاتف. لن تُفتح الطرفية العربية.")
        TextButton(onClick = { meta = TermuxClient.status(context) }) {
            Text("تحديث الحالة")
        }
        Button(enabled = !busy && meta.optBoolean("installed"), onClick = {
            busy = true
            result = "جارٍ تجهيز NewAl في Termux. قد يستغرق التنزيل الأول دقيقة..."
            scope.launch {
                try {
                    val reply = NewAlTermuxBridge.setup(context, owner)
                    result = reply.optString("content", "تعذّر تجهيز المحرك.")
                } catch (_: Exception) {
                    result = "تعذّر تجهيز المحرك؛ تحقق من الإنترنت وصلاحيات Termux."
                } finally { busy = false }
            }
        }) { Text("تجهيز وكيل NewAl تلقائياً") }
        OutlinedTextField(
            value = command,
            onValueChange = { command = it.take(3000) },
            label = { Text("أمر Termux") },
            textStyle = TextStyle(textDirection = TextDirection.Ltr),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )
        Button(enabled = !busy && command.isNotBlank(), onClick = {
            val queued = command
            busy = true
            result = "جارٍ تنفيذ الأمر داخل Termux..."
            scope.launch {
                try {
                    val reply = execute(
                        JSONObject().put("executable", "bash")
                            .put("arguments", JSONArray(listOf("-lc", queued))).put("timeout", 30)
                    )
                    result = describe(reply)
                } catch (_: Exception) {
                    result = "حدث خطأ في الاتصال بـTermux؛ لم يتم تأكيد تنفيذ الأمر."
                } finally { busy = false }
            }
        }) { Text(if (busy) "العمل جارٍ..." else "نفّذ داخل Termux") }
        if (result.isNotBlank()) {
            Text("نتيجة التنفيذ", style = MaterialTheme.typography.titleMedium)
            Text(result, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
