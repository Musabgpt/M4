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
    Integration("Code-OSS", "Code-OSS editor, Open VSX and language servers • large optional download", "developer-host"),
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
    var refresh by remember { mutableStateOf(0) }
    DisposableEffect(context) {
        val lifecycle = (context as? androidx.activity.ComponentActivity)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    val termuxInstalled = remember(refresh) { com.musabgpt.m4.terminal.TermuxClient.status(context).optBoolean("installed") }
    val github = remember(refresh) { com.musabgpt.m4.github.GitHubClient(context.applicationContext).status() }
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
                        val missingTermux = integration.destination == "termux" && !termuxInstalled
                        val connectGithub = integration.destination == "github" && !github.optBoolean("connected")
                        Text(when {
                            integration.destination == "developer-host" -> "محرك اختياري"
                            missingTermux -> "غير مثبت"
                            integration.destination == "github" -> if (github.optBoolean("connected")) "متصل: ${github.optString("login")}" else "جاهز للربط"
                            else -> "مدمج"
                        }, style = MaterialTheme.typography.labelSmall)
                        Button(onClick = {
                            if (missingTermux) runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/")))
                            }.onFailure { error = it.message.orEmpty() }
                            else if (integration.destination == "developer-host") open("developer-host-install")
                            else open(if (connectGithub) "github-connect" else integration.destination)
                        }) { Text(when { integration.destination == "developer-host" -> "تثبيت وفتح"; missingTermux -> "تثبيت"; connectGithub -> "ربط الحساب"; else -> "فتح" }) }

                    }
                }
            }
        }
    }
}
