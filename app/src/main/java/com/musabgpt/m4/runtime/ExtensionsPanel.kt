package com.musabgpt.m4.runtime

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private data class Integration(val name: String, val description: String, val destination: String)
private val integrations = listOf(
    Integration("GitHub", "Repositories, issues, pull requests and Actions", "github"),
    Integration("GitLab", "Projects, merge requests and pipelines", "gitlab"),
    Integration("Termux", "Optional external command runner", "termux"),
    Integration("MCP, Skills & Hooks", "Connect tool servers and configure agent capabilities", "tool-settings"),
    Integration("Browser", "Preview and browse from the workspace", "browser"),
    Integration("Tasks", "Schedule and inspect background tasks", "tasks")
)

@Composable
fun ExtensionsPanel(open: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val termuxInstalled = remember { runCatching { context.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Extensions", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search integrations") }, modifier = Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(integrations.filter { (it.name + it.description).contains(query, true) }) { integration ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(integration.name, style = MaterialTheme.typography.titleMedium)
                        Text(integration.description, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { open(integration.destination) }) { Text(if (integration.destination in setOf("github", "gitlab")) "Connect / Manage" else "Open") }
                        if (integration.destination == "termux" && !termuxInstalled) {
                            Text("Termux is not installed", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))) }
                                    .onFailure { error = it.message.orEmpty() }
                            }) { Text("Get Termux") }
                        }
                    }
                }
            }
        }
    }
}
