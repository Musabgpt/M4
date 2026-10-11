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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import org.json.JSONObject

@Composable
fun SchedulerPanel(owner: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tasks by remember { mutableStateOf(emptyList<ScheduledTask>()) }
    var command by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("hourly") }
    var delayText by remember { mutableStateOf("60") }
    var intervalText by remember { mutableStateOf("60") }
    var timezone by remember { mutableStateOf(ZoneId.systemDefault().id) }
    var event by remember { mutableStateOf("chat_finished") }
    var condition by remember { mutableStateOf("charging") }
    var percent by remember { mutableStateOf("30") }
    var repository by remember { mutableStateOf("Musabgpt/M4") }
    var createOpen by remember { mutableStateOf(false) }
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
        TextButton(onClick = { createOpen = !createOpen }) { Text(if (createOpen) "إخفاء النموذج" else "+ مهمة جديدة") }
        if (createOpen) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("اسم المهمة") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(command, { command = it }, label = { Text("الأمر داخل /root/projects/M4") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("once" to "مرة", "hourly" to "كل ساعة", "daily" to "يومي", "weekly" to "أسبوعي", "monthly" to "شهري", "interval" to "فاصل زمني", "condition_watch" to "عند تحقق شرط", "on_app_event" to "حدث التطبيق", "on_ci_event" to "نتيجة CI").forEach { (value, label) ->
                TextButton(onClick = { type = value }) { Text((if (type == value) "● " else "") + label) }
            }
        }
        OutlinedTextField(delayText, { delayText = it }, label = { Text("أول تشغيل بعد دقائق") })
        OutlinedTextField(timezone, { timezone = it }, label = { Text("المنطقة الزمنية") })
        if (type in setOf("interval", "condition_watch", "on_ci_event")) OutlinedTextField(intervalText, { intervalText = it }, label = { Text("فاصل المتابعة بالدقائق (15 أو أكثر)") })
        if (type == "on_app_event") Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("app_started" to "فتح التطبيق", "chat_finished" to "انتهاء محادثة", "artifact_created" to "إنشاء مرفق").forEach { (key, label) -> TextButton(onClick = { event = key }) { Text((if (event == key) "● " else "") + label) } }
        }
        if (type == "condition_watch") {
            Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("charging" to "الشحن", "battery_above" to "البطارية", "network_connected" to "اتصال الشبكة").forEach { (key, label) -> TextButton(onClick = { condition = key }) { Text((if (condition == key) "● " else "") + label) } }
            if (condition == "battery_above") OutlinedTextField(percent, { percent = it }, label = { Text("أقل نسبة بطارية") })
        }
        if (type == "on_ci_event") OutlinedTextField(repository, { repository = it }, label = { Text("مستودع GitHub: owner/repo") })
        Button(onClick = { scope.launch {
            try {
                val trigger = JSONObject().apply { when(type) { "on_app_event" -> put("event", event); "condition_watch" -> { put("condition", condition); if (condition == "battery_above") put("percent", percent.toInt().also { require(it in 0..100) }) }; "on_ci_event" -> put("repository", repository.trim()) } }
                approval = withContext(Dispatchers.IO) { TaskDatabase.get(context).create(name.trim(), command.trim(), owner,
                    type, delayText.toLong(), false, intervalText.toLong(), trigger, timezone.trim()) }
                error = ""; name = ""; command = ""
                createOpen = false
            } catch (e: Exception) { error = "تعذر إنشاء المهمة: ${e.message}" }
        } }, enabled = name.isNotBlank() && command.isNotBlank()) { Text("مراجعة وإنشاء") }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(task.name)
                        Text("${task.state} • ${task.type}", style = MaterialTheme.typography.labelSmall)
                        if (task.enabled && task.type != "on_app_event") Text("الموعد المتوقع: " + Instant.ofEpochMilli(task.nextRun).atZone(ZoneId.of(task.timezone)))
                        Text(task.command, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { approval = task }) { Text(if (task.enabled) "نطاق الأمر" else "تفعيل") }
                            TextButton(onClick = { TaskScheduler.pause(context, task.id) }) { Text("إيقاف") }
                            TextButton(onClick = { TaskScheduler.runNow(context, task) }, enabled = task.enabled) { Text("الآن") }
                            TextButton(onClick = { scope.launch { history = withContext(Dispatchers.IO) { TaskDatabase.get(context).lastRun(task.id) } } }) { Text("السجل") }
                            TextButton(onClick = { name = task.name + " (نسخة)"; command = task.command; type = task.type; timezone = task.timezone; intervalText = task.intervalMinutes.toString(); val trigger = JSONObject(task.trigger); event = trigger.optString("event", "chat_finished"); condition = trigger.optString("condition", "charging"); repository = trigger.optString("repository", "Musabgpt/M4"); percent = trigger.optInt("percent", 30).toString(); createOpen = true }) { Text("نسخ وتعديل") }
                            TextButton(onClick = { scope.launch {
                                try { withContext(Dispatchers.IO) { TaskScheduler.delete(context, task.id) } }
                                catch (e: Exception) { error = "تعذر الحذف: ${e.message}" }
                            } }) { Text("حذف") }
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
                approval = null
                scope.launch {
                    try { withContext(Dispatchers.IO) { TaskScheduler.enable(context, task) } }
                    catch (e: Exception) { error = "تعذرت الجدولة: ${e.message}" }
                }
            }) { Text("موافقة وتفعيل") } }, dismissButton = { TextButton(onClick = { approval = null }) { Text("إلغاء") } })
    }
    history?.let { report -> AlertDialog(onDismissRequest = { history = null }, title = { Text("آخر تشغيل حقيقي") },
        text = { Text(report.take(10000)) }, confirmButton = { TextButton(onClick = { history = null }) { Text("إغلاق") } }) }
}
