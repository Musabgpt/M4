package com.musabgpt.m4.runtime

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val destinations = listOf(
    "files" to "Explorer", "chat" to "Agent", "github" to "GitHub",
    "gitlab" to "GitLab", "terminal" to "Terminal", "tools" to "Extensions",
    "browser" to "Preview", "tasks" to "Tasks", "termux" to "Termux"
)

@Composable
fun WorkbenchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFAEC9FF), onPrimary = Color(0xFF101820),
        background = Color(0xFF181818), surface = Color(0xFF1F1F1F),
        surfaceVariant = Color(0xFF282828), onBackground = Color(0xFFE5E5E5),
        onSurface = Color(0xFFE5E5E5), onSurfaceVariant = Color(0xFFA0A0A0),
        outline = Color(0xFF383838)
    ), content = content)
}

/** One persistent workbench frame; integrations are destinations, not chat prompts. */
@Composable
fun WorkbenchShell(panel: String, open: (String) -> Unit, content: @Composable () -> Unit) {
    var commands by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("M4", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(8.dp))
                    OutlinedButton(onClick = { commands = true }, modifier = Modifier.weight(1f)) {
                        Text("⌕  M4 / Search commands", fontSize = 12.sp, maxLines = 1)
                    }
                    TextButton(onClick = { open("chat") }) { Text("Agent") }
                }
                HorizontalDivider()
                Row(Modifier.weight(1f)) {
                    Column(Modifier.width(76.dp).fillMaxHeight().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        destinations.filter { it.first !in setOf("termux", "gitlab", "tasks") }.forEach { (key, label) ->
                            TextButton(onClick = { open(key) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.fillMaxWidth().height(56.dp).testTag("workbench-$key"), colors = ButtonDefaults.textButtonColors(contentColor = if (panel == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                                Text(label, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("M4 workspace", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    destinations.filter { it.first in setOf("gitlab", "tasks", "termux") }.forEach { (key, label) -> TextButton(onClick = { open(key) }) { Text(label, fontSize = 10.sp) } }
                }
            }
        }
        if (commands) AlertDialog(onDismissRequest = { commands = false }, title = { Text("Go to…") }, text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search workspace commands") })
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    destinations.filter { it.second.contains(query, true) }.forEach { (key, label) ->
                        TextButton(onClick = { open(key); commands = false; query = "" }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { commands = false }) { Text("Close") } })
    }
}
