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

/** Cursor-like command surface backed by Termux RUN_COMMAND, with receipts. */
@Composable
fun TermuxPanel(owner: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var command by remember { mutableStateOf("pwd") }
    var cwd by remember { mutableStateOf("/data/data/com.termux/files/home") }
    var output by remember { mutableStateOf(TermuxClient.status(context).toString(2)) }
    var busy by remember { mutableStateOf(false) }

    fun runShell(value: String) {
        command = value; busy = true
        scope.launch {
            output = try {
                withContext(Dispatchers.IO) {
                    TermuxClient.run(context, JSONObject().put("executable", "bash")
                        .put("arguments", JSONArray().put("-lc").put(value))
                        .put("cwd", cwd).put("timeout", 120), owner).toString(2)
                }
            } catch (e: Exception) { e.message.orEmpty() }
            finally { busy = false }
        }
    }

    val setup = "pkg update -y && pkg install -y git python nodejs openssh && " +
        "if [ -d \"\$HOME/M4/.git\" ]; then git -C \"\$HOME/M4\" pull --ff-only; else git clone https://github.com/Musabgpt/M4.git \"\$HOME/M4\"; fi"
    val test = "cd \"\$HOME/M4\" && python3 -m unittest discover -s agent_tests -q"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Termux • Cursor Terminal", style = MaterialTheme.typography.titleLarge)
        Text("التنفيذ يمر عبر RUN_COMMAND ويُحفظ له إيصال فعلي. ثبّت Termux من المصدر الرسمي ثم فعّل allow-external-apps.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(cwd, { cwd = it }, label = { Text("مجلد العمل") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(command, { command = it }, label = { Text("أمر bash") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !busy, onClick = { runShell(command) }) { Text("تشغيل") }
            TextButton(enabled = !busy, onClick = { runShell(setup) }) { Text("تجهيز M4") }
            TextButton(enabled = !busy, onClick = { runShell(test) }) { Text("اختبار") }
        }
        TextButton(enabled = !busy, onClick = { runShell("git --version && python3 --version && node --version") }) { Text("فحص الأدوات") }
        HorizontalDivider()
        Text("النتيجة", style = MaterialTheme.typography.titleMedium)
        Text(output, style = MaterialTheme.typography.bodySmall)
    }
}
