package com.musabgpt.m4.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.musabgpt.m4.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

@Composable
fun InlineEditDialog(document: EditorDocument, provider: Provider, atCursor: Boolean = false, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val gateway = remember { ChatGateway() }
    val before = remember { document.content }
    val selection = remember { document.value.selection }
    var instruction by remember { mutableStateOf(if (atCursor) "Complete the code at the cursor" else "") }
    var candidate by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel(); gateway.stop() } }
    AlertDialog(onDismissRequest = close, title = { Text("${if (atCursor) "Complete at cursor" else "Edit with AI"} · ${document.file.name}") }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(provider.name, style = MaterialTheme.typography.labelSmall)
            OutlinedTextField(instruction, { instruction = it }, modifier = Modifier.testTag("inline-instruction"), enabled = !busy && candidate == null, label = { Text("ما التعديل المطلوب؟") })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (route.isNotBlank()) Text(route, style = MaterialTheme.typography.labelSmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            candidate?.let { CodeComparison(before, it) }
        }
    }, confirmButton = {
        if (candidate != null) TextButton(onClick = {
            if (document.content != before) error = "تغيّر المحرر أثناء الطلب؛ أعد توليد الاقتراح"
            else { document.content = candidate!!; close() }
        }) { Text("قبول في المحرر") }
        else TextButton(enabled = instruction.isNotBlank() && !busy, onClick = {
            if (before.length > 12000) { error = "هذا الملف يتجاوز حجم سياق التعديل المباشر (12000 حرف)" }
            else {
                busy = true; error = ""
                job = scope.launch {
                    try {
                        val reply = StringBuilder()
                        withTimeout(120000) {
                            val task = if (atCursor) {
                                "Return ONLY the code to insert at <M4_CURSOR> inside one fenced code block. Do not repeat surrounding code. Instruction: ${instruction.take(2000)}\nFile: ${document.file.name}\n" +
                                    before.substring(0, selection.min) + "<M4_CURSOR>" + before.substring(selection.max)
                            } else {
                                "Edit the entire file below according to this instruction. Return ONLY the complete updated file inside one fenced code block. No explanation. Preserve unrelated code.\nInstruction: ${instruction.take(2000)}\nFile: ${document.file.name}\n```\n$before\n```"
                            }
                            gateway.stream(provider, listOf(ChatMessage("user", task
                            ))).collect { event -> when (event) {
                                is ChatEvent.Route -> route = event.name
                                is ChatEvent.Text -> { reply.append(event.value); require(reply.length <= 110000) { "الاقتراح كبير جدًا" } }
                            } }
                        }
                        val generated = InlineEditResponse.code(reply.toString())
                        candidate = if (atCursor) applyCursorCompletion(before, selection.min, selection.max, generated) else generated
                    } catch (failure: TimeoutCancellationException) { error = "انتهت مهلة النموذج؛ يمكنك إعادة المحاولة" }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message.orEmpty() }
                    finally { busy = false }
                }
            }
        }) { Text("اقتراح تعديل") }
    }, dismissButton = { TextButton(onClick = { job?.cancel(); gateway.stop(); close() }) { Text(if (candidate == null) "إلغاء" else "رفض") } })
}

@Composable
fun CodeComparison(before: String, after: String) {
    val oldLines = before.lines()
    val newLines = after.lines()
    Column {
        repeat(maxOf(oldLines.size, newLines.size)) { index ->
            val old = oldLines.getOrNull(index)
            val new = newLines.getOrNull(index)
            if (old == new) Text("  ${old.orEmpty()}", fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            else {
                if (old != null) Text("− $old", color = androidx.compose.ui.graphics.Color(0xFFFF9090), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                if (new != null) Text("+ $new", color = androidx.compose.ui.graphics.Color(0xFF8FDBA1), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

fun applyCursorCompletion(source: String, start: Int, end: Int, insertion: String): String {
    require(start in 0..source.length && end in start..source.length)
    return source.replaceRange(start, end, insertion)
}
