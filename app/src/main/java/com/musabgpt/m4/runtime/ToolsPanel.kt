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
import io.musab.arabicterminal.UiControlService

/** User-facing tools, no editable raw JSON or pretend MCP registrations. */
@Composable
fun ToolsPanel(vm: ChatViewModel, showChat: () -> Unit) {
    val context = LocalContext.current
    var termux by remember { mutableStateOf(TermuxClient.status(context)) }
    fun open(route: String) {
        vm.send(route)
        showChat()
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("الأدوات والقدرات", style = MaterialTheme.typography.titleLarge)
        Text("محرك التنفيذ: Termux المثبت على الهاتف")
        Text("Termux: " + if (termux.optBoolean("installed")) "مثبت" else "غير مثبت")
        Text("صلاحية التنفيذ: " +
            if (termux.optBoolean("permission_granted")) "ممنوحة" else "مطلوبة")
        TextButton(onClick = { termux = TermuxClient.status(context) }) { Text("تحديث حالة Termux") }
        Button(onClick = { open("/termux") }) { Text("فتح أوامر Termux داخل M4") }
        OutlinedButton(onClick = { open("/setup") }) { Text("تجهيز وكيل NewAl تلقائياً") }
        HorizontalDivider()
        Text("GitHub والمشاريع", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { open("/github") }) { Text("فتح GitHub والمستودعات") }
        Row {
            TextButton(onClick = { open("/files") }) { Text("الملفات") }
            TextButton(onClick = { open("/tasks") }) { Text("المهام المجدولة") }
        }
        HorizontalDivider()
        Text("التحكم بالهاتف", style = MaterialTheme.typography.titleMedium)
        Text(
            if (UiControlService.current() != null)
                "خدمة التحكم بالهاتف مفعّلة"
            else "خدمة التحكم بالهاتف غير مفعّلة"
        )
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }) { Text("فتح إعدادات إمكانية الوصول") }
        HorizontalDivider()
        Text("المهارات وMCP", style = MaterialTheme.typography.titleMedium)
        Text("تحتاج هذه الأدوات إلى تجهيز وكيل NewAl. لا أعتبر الاتصال بها ناجحاً قبل اختباره.")
        TextButton(onClick = { open("/doctor") }) { Text("فحص المحرك") }
    }
}
