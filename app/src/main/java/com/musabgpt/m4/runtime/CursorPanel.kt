package com.musabgpt.m4.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musabgpt.m4.ChatViewModel

/** Cursor-style command center: every integration is reachable from one explicit button. */
@Composable
fun CursorPanel(vm: ChatViewModel, open: (String) -> Unit) {
    data class Action(val label: String, val command: String? = null, val panel: String? = null)
    val composer = listOf(
        Action("Composer: خطة تنفيذ", "اكتب خطة تنفيذ للمهمة الحالية مع الملفات والاختبارات المطلوبة ثم انتظر موافقتي."),
        Action("Inline Edit", "عدّل الكود الذي سأحدده أو أرسله، واعرض diff قبل الحفظ."),
        Action("Explain", "اشرح الملف أو المقطع الذي سأرسله، مع ذكر الافتراضات والمخاطر."),
        Action("Refactor", "اقترح إعادة هيكلة آمنة للكود الذي سأرسله مع الحفاظ على السلوك والاختبارات."),
        Action("Generate Tests", "اكتب اختبارات ذات معنى للكود الذي سأرسله وشغّلها عبر الطرفية المدمجة."),
        Action("Debug", "حلّل الخطأ الذي سأرسله، أعد إنتاجه، ثم أصلحه مع دليل من الاختبارات."),
        Action("Review", "راجع التغييرات الحالية كمراجعة Pull Request، ورتّب الملاحظات حسب الخطورة.")
    )
    val integrations = listOf(
        Action("الملفات / Editor", panel = "files"), Action("الطرفية المدمجة", panel = "terminal"),
        Action("Termux اختياري", panel = "termux"), Action("Browser", panel = "browser"),
        Action("GitHub", panel = "github"), Action("GitLab", panel = "gitlab"),
        Action("المهارات", "/skills"), Action("Extensions / Tools", panel = "tools"),
        Action("MCP", "/mcp"), Action("Hooks", "/hooks"), Action("الذاكرة", "/memory قرارات المشروع"),
        Action("المهام المجدولة", panel = "tasks"), Action("الهاتف", "/phone screen")
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cursor Workbench", style = MaterialTheme.typography.headlineSmall)
        Text("محادثة مع سياق المشروع، محرر ملفات، Composer، مراجعة، أدوات، ومصادر Git. كل إجراء حساس يطلب موافقة واضحة.", style = MaterialTheme.typography.bodySmall)
        Text("Composer", style = MaterialTheme.typography.titleMedium)
        composer.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    OutlinedButton(Modifier.weight(1f), onClick = { vm.send(action.command.orEmpty()); open("chat") }) { Text(action.label) }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        HorizontalDivider()
        Text("Integrations", style = MaterialTheme.typography.titleMedium)
        integrations.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    OutlinedButton(Modifier.weight(1f), onClick = {
                        action.panel?.let(open) ?: run { vm.send(action.command.orEmpty()); open("chat") }
                    }) { Text(action.label) }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
