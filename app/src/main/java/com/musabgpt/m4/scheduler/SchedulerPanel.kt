package com.musabgpt.m4.scheduler

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Composable
fun SchedulerPanel(owner: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(emptyList<ScheduledTask>()) }
    var command by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("hourly") }
    var delayText by remember { mutableStateOf("60") }
    var error by remember { mutableStateOf("") }
    var approval by remember { mutableStateOf<ScheduledTask?>(null) }
    var history by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(owner) {
        while (true) {
            tasks = withContext(Dispatchers.IO) { TaskDatabase.get(context).list(owner) }
            delay(1500)
        }
    }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("مهام Alpine دائمة", style = MaterialTheme.typography.titleMedium)
        Text("أوقات التنفيذ نوافذ متوقعة؛ قد يؤخرها أندرويد والبطارية. الأوامر لها صلاحيات المشروع المعتادة وحد أقصى دقيقتان.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(name, { name = it }, label = { Text("اسم المهمة") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(command, { command = it }, label = { Text("الأمر داخل /root/projects/M4") }, modifier = Modifier.fillMaxWidth())
        Row {
            listOf("once" to "مرة", "hourly" to "كل ساعة", "daily" to "يومي").forEach { (value, label) ->
                TextButton(onClick = { type = value }) { Text((if (type == value) "● " else "") + label) }
            }
        }
        OutlinedTextField(delayText, { delayText = it }, label = { Text("أول تشغيل بعد دقائق") })
        Button(onClick = { scope.launch {
            try {
                approval = withContext(Dispatchers.IO) { TaskDatabase.get(context).create(name.trim(), command.trim(), owner,
                    type, delayText.toLong(), false) }
                error = ""; name = ""; command = ""
            } catch (e: Exception) { error = "تعذر إنشاء المهمة: ${e.message}" }
        } }, enabled = name.isNotBlank() && command.isNotBlank()) { Text("مراجعة وإنشاء") }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(task.name)
                        Text("${task.state} • ${task.type}", style = MaterialTheme.typography.labelSmall)
                        if (task.enabled) Text("الموعد المتوقع: " + Instant.ofEpochMilli(task.nextRun).atZone(ZoneId.of(task.timezone)))
                        Text(task.command, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { approval = task }) { Text(if (task.enabled) "نطاق الأمر" else "تفعيل") }
                            TextButton(onClick = { TaskScheduler.pause(context, task.id) }) { Text("إيقاف") }
                            TextButton(onClick = { TaskScheduler.runNow(context, task) }, enabled = task.enabled) { Text("الآن") }
                            TextButton(onClick = { scope.launch { history = withContext(Dispatchers.IO) { TaskDatabase.get(context).lastRun(task.id) } } }) { Text("السجل") }
                        }
                    }
                }
            }
        }
    }
    approval?.let { task ->
        AlertDialog(onDismissRequest = { approval = null }, title = { Text("موافقة نطاق المهمة") },
            text = { Text("${task.name}\n${task.command}\nالتكرار: ${task.type}\nسيُنفذ الأمر في الخلفية بصلاحيات ملفات التطبيق. الموافقة تخص هذا الأمر والتكرار؛ لا تمنح وصول GitHub أو إرسال رسائل.\n${task.id}") },
            confirmButton = { TextButton(onClick = {
                try { TaskScheduler.enable(context, task); approval = null }
                catch (e: Exception) { error = "تعذرت الجدولة: ${e.message}"; approval = null }
            }) { Text("موافقة وتفعيل") } }, dismissButton = { TextButton(onClick = { approval = null }) { Text("إلغاء") } })
    }
    history?.let { report -> AlertDialog(onDismissRequest = { history = null }, title = { Text("آخر تشغيل حقيقي") },
        text = { Text(report.take(10000)) }, confirmButton = { TextButton(onClick = { history = null }) { Text("إغلاق") } }) }
}
