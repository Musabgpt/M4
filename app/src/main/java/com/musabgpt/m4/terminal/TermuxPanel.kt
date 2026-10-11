package com.musabgpt.m4.terminal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Single-window command results backed by the installed Termux app. */
@Composable
fun TermuxPanel(owner: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("python --version") }
    var result by remember { mutableStateOf("") }
    var pendingId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(TermuxClient.status(context).toString(2)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Termux — محرك M4", style = MaterialTheme.typography.titleLarge)
        Text("الأوامر تعمل داخل Termux المثبت على الهاتف وتظهر نتائجها هنا، دون الطرفية العربية.")
        Text(status, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { status = TermuxClient.status(context).toString(2) }) {
            Text("تحديث حالة الربط")
        }
        Text("يتطلب الربط سماح Android لتشغيل أوامر Termux، وخيار allow-external-apps=true داخل Termux.")
        OutlinedTextField(
            value = command, onValueChange = { command = it.take(3000) },
            label = { Text("أمر داخل Termux") }, modifier = Modifier.fillMaxWidth(),
            maxLines = 4
        )
        Button(enabled = !busy && command.isNotBlank(), onClick = {
            busy = true
            val queued = command
            scope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        TermuxClient.run(context, JSONObject()
                            .put("executable", "bash")
                            .put("arguments", JSONArray(listOf("-lc", queued)))
                            .put("timeout", 35), owner)
                    }
                    pendingId = response.optString("command_id")
                    result = if (response.optString("status") == "succeeded")
                        response.optString("stdout") + response.optString("stderr")
                    else response.toString(2)
                } catch (failure: Exception) { result = "تعذر تنفيذ الأمر: " + failure.message }
                finally { busy = false }
            }
        }) { Text(if (busy) "العمل جارٍ..." else "نفّذ في Termux") }
        if (pendingId.isNotBlank()) {
            TextButton(onClick = {
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        TermuxClient.output(context, owner, pendingId).toString(2)
                    }
                }
            }) { Text("استرجاع نتيجة الأمر") }
        }
        if (result.isNotBlank()) {
            Text("نتيجة التنفيذ", style = MaterialTheme.typography.titleMedium)
            Text(result, style = MaterialTheme.typography.bodyMedium)
        }
        TextButton(onClick = { command = "newal-termux status" }) {
            Text("فحص وكيل NewAl داخل Termux")
        }
    }
}
