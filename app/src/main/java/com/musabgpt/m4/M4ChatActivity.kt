package com.musabgpt.m4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musabgpt.m4.artifacts.ArtifactRepository
import com.musabgpt.m4.artifacts.ChatArtifact
import com.musabgpt.m4.browser.BrowserPanel
import com.musabgpt.m4.runtime.FilesPanel
import com.musabgpt.m4.runtime.ChatEventStream
import com.musabgpt.m4.runtime.RuntimeEvent
import com.musabgpt.m4.scheduler.SchedulerPanel
import com.musabgpt.m4.terminal.EmbeddedAlpineBackend
import com.musabgpt.m4.terminal.TerminalPanel
import com.musabgpt.m4.terminal.TermuxPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chat, real terminal, files, browser and durable tasks share this Activity. */
class M4ChatActivity : ComponentActivity() {
    private lateinit var browser: com.musabgpt.m4.browser.BrowserController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = com.musabgpt.m4.browser.BrowserController(this)
        if (appEventSent.compareAndSet(false, true)) lifecycleScope.launchWhenStarted {
            withContext(Dispatchers.IO) { com.musabgpt.m4.scheduler.TaskScheduler.event(this@M4ChatActivity, "app_started", processEventId) }
        }
        setContent {
            com.musabgpt.m4.runtime.WorkbenchTheme {
                val vm: ChatViewModel = viewModel()
                val terminal = vm.terminal
                val editorState: com.musabgpt.m4.runtime.WorkspaceEditorState = viewModel()
                LaunchedEffect(Unit) { editorState.prepare(applicationContext) }
                val permissionRequest by com.musabgpt.m4.runtime.HostPermissionRequest.pending.collectAsState()
                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted -> com.musabgpt.m4.runtime.HostPermissionRequest.resolve(granted) }
                LaunchedEffect(permissionRequest) { permissionRequest?.let { permissionLauncher.launch(it) } }
                val approval by com.musabgpt.m4.runtime.CapabilityBroker.pending.collectAsState()
                approval?.let { request ->
                    AlertDialog(onDismissRequest = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, false) },
                        title = { Text("موافقة: ${request.action}") }, text = { Text(request.details) },
                        confirmButton = { Column {
                            TextButton(onClick = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, true) }) { Text("السماح مرة") }
                            TextButton(onClick = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, true, true) }) { Text("السماح بهذا الإجراء في المحادثة") }
                        } },
                        dismissButton = { TextButton(onClick = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, false) }) { Text("رفض") } })
                }
                val owner by vm.currentId.collectAsState()
                var panel by rememberSaveable { mutableStateOf("files") }
                val requestedPanel by vm.requestedPanel.collectAsState()
                LaunchedEffect(requestedPanel) { requestedPanel?.let { panel = it; vm.consumedPanel() } }
                browser.onNavigate = { panel = "browser" }
                var attachments by remember { mutableStateOf(emptyList<ChatArtifact>()) }
                var events by remember { mutableStateOf(emptyList<RuntimeEvent>()) }
                var eventLimit by remember(owner) { mutableStateOf(100) }
                LaunchedEffect(panel) { if (panel == "terminal") terminal.open() }
                LaunchedEffect(owner) {
                    while (true) {
                        attachments = withContext(Dispatchers.IO) {
                            ArtifactRepository.importPublished(this@M4ChatActivity)
                            ArtifactRepository.list(this@M4ChatActivity, owner)
                        }
                        events = withContext(Dispatchers.IO) { ChatEventStream.read(this@M4ChatActivity, owner, limit = eventLimit) }
                        delay(1500)
                    }
                }
                com.musabgpt.m4.runtime.WorkbenchShell(panel, { panel = it }) {
                    Box(Modifier.fillMaxSize()) {
                        Surface(Modifier.fillMaxSize()) { when (panel) {
                        "developer-host" -> com.musabgpt.m4.runtime.DeveloperHostPanel()
                        "browser" -> BrowserPanel(browser)
                        "cursor" -> com.musabgpt.m4.runtime.FilesPanel(editorState) { prompt -> vm.send(prompt); panel = "chat" }
                        "chat" -> ChatScreen(openTerminal = { panel = "terminal"; terminal.open() },
                                runAgent = { goal -> vm.send(goal) },
                                readAgentProgress = { AgentProgressStore.read(this@M4ChatActivity) },
                                artifacts = attachments, runtimeEvents = events, executeCommand = { command -> terminal.execute(command); panel = "terminal" }, loadOlderEvents = { eventLimit = (eventLimit + 200).coerceAtMost(5000) }, vm = vm)
                            "terminal" -> TerminalPanel(terminal)
                            "termux" -> TermuxPanel(owner)
                            "files" -> FilesPanel(editorState) { prompt -> vm.send(prompt); panel = "chat" }
                            "tasks" -> SchedulerPanel(owner)
                            "github" -> com.musabgpt.m4.github.GitHubPanel(owner)
                            "github-connect" -> com.musabgpt.m4.github.GitHubPanel(owner, autoConnect = true)
                            "gitlab" -> com.musabgpt.m4.github.GitLabPanel(owner)
                            "tools" -> com.musabgpt.m4.runtime.ExtensionsPanel { panel = it }
                            "tool-settings" -> com.musabgpt.m4.runtime.ToolsPanel(vm, { panel = "chat" }, { panel = "termux" })
                        } }
                    }

                }
            }
        }
    }
    override fun onDestroy() { browser.close(); super.onDestroy() }
    companion object {
        private val appEventSent = java.util.concurrent.atomic.AtomicBoolean(false)
        private val processEventId = java.util.UUID.randomUUID().toString()
    }
}
