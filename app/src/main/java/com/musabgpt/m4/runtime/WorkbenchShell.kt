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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Code

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
        Scaffold(
            modifier = Modifier.fillMaxSize().testTag("workbench-shell"),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, "M4", tint = MaterialTheme.colorScheme.primary)
                        Text("  M4 / " + (destinations.firstOrNull { it.first == panel }?.second ?: "Workspace"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { commands = true }, modifier = Modifier.testTag("workbench-commands")) { Icon(Icons.Default.Search, "Search commands") }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                        Triple("files", "Files", Icons.Default.Folder),
                        Triple("chat", "Agent", Icons.Default.Chat),
                        Triple("terminal", "Terminal", Icons.Default.Terminal),
                        Triple("tools", "Extensions", Icons.Default.Extension)
                    ).forEach { (key, label, icon) ->
                        NavigationBarItem(selected = panel == key, onClick = { open(key) },
                            modifier = Modifier.testTag("workbench-$key"),
                            icon = { Icon(icon, label) }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) { content() }
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
