package com.musabgpt.m4.runtime

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musabgpt.m4.ChatViewModel
import com.musabgpt.m4.terminal.TermuxClient
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.UiControlService
import org.json.JSONArray
import org.json.JSONObject

@Composable fun ToolsPanel(vm: ChatViewModel, showChat: () -> Unit, openTermux: () -> Unit = {}) {
    val context = LocalContext.current
    var executable by remember { mutableStateOf("python") }
    var arguments by remember { mutableStateOf("[\"--version\"]") }
    var mcpConfig by remember { mutableStateOf("{\"name\":\"local\",\"transport\":\"stdio\",\"command\":[\"python3\",\"server.py\"]}") }
    var error by remember { mutableStateOf("") }
    fun command(text: String) { vm.send(text); showChat() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("الأدوات والقدرات", style = MaterialTheme.typography.titleLarge)
        Text("الطرفية: " + if (LinuxEnvironment.installed(context)) "مثبتة" else "تُجهّز عند أول تشغيل")
        Text("التحكم بالهاتف: " + if (UiControlService.current() != null) "الخدمة مفعلة" else "غير مفعل")
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("إعدادات التحكم بالهاتف") }
        Row { TextButton(onClick = { command("/phone apps") }) { Text("التطبيقات المثبتة") }; TextButton(onClick = { command("/phone screen") }) { Text("قراءة الشاشة") } }
        Text("Termux (إضافة اختيارية)", style = MaterialTheme.typography.titleMedium)
        Text(TermuxClient.status(context).toString())
        TextButton(onClick = openTermux) { Text("فتح لوحة Termux") }
        OutlinedTextField(executable, { executable = it }, label = { Text("اسم البرنامج") })
        OutlinedTextField(arguments, { arguments = it }, label = { Text("وسائط البرنامج كـ JSON array") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { try { command("/termux run " + JSONObject().put("executable", executable).put("arguments", JSONArray(arguments)).toString()) } catch (e: Exception) { error = e.message.orEmpty() } }) { Text("مراجعة أمر Termux وتشغيله") }
        Text("Skills / MCP / Hooks", style = MaterialTheme.typography.titleMedium)
        Row { TextButton(onClick = { command("/skills") }) { Text("المهارات") }; TextButton(onClick = { command("/mcp") }) { Text("اتصالات MCP") }; TextButton(onClick = { command("/hooks") }) { Text("Hooks") } }
        OutlinedTextField(mcpConfig, { mcpConfig = it }, label = { Text("إعداد اتصال MCP") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { try { JSONObject(mcpConfig); command("/mcp connect $mcpConfig") } catch (e: Exception) { error = e.message.orEmpty() } }) { Text("مراجعة وربط MCP") }
        Row { TextButton(onClick = { command("/tools") }) { Text("الأدوات المسجلة") }; TextButton(onClick = { command("/doctor") }) { Text("فحص البيئة") }; TextButton(onClick = { command("/agents") }) { Text("تقارير المراجعة") } }
        TextButton(onClick = { command("/memory قرارات المشروع") }) { Text("ذاكرة المشروع") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
    }
}
