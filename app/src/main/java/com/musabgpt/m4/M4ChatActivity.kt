package com.musabgpt.m4

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chat, real terminal, files, browser and durable tasks share this Activity. */
class M4ChatActivity : ComponentActivity() {
    private lateinit var browser: com.musabgpt.m4.browser.BrowserController
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        browser = com.musabgpt.m4.browser.BrowserController(this)
        setContent {
            ChatTheme {
                val vm: ChatViewModel = viewModel()
                val terminal = vm.terminal
                val approval by com.musabgpt.m4.runtime.CapabilityBroker.pending.collectAsState()
                approval?.let { request ->
                    AlertDialog(onDismissRequest = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, false) },
                        title = { Text("موافقة: ${request.action}") }, text = { Text(request.details) },
                        confirmButton = { TextButton(onClick = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, true) }) { Text("السماح مرة") } },
                        dismissButton = { TextButton(onClick = { com.musabgpt.m4.runtime.CapabilityBroker.resolve(request.id, false) }) { Text("رفض") } })
                }
                val owner by vm.currentId.collectAsState()
                var panel by rememberSaveable { mutableStateOf("chat") }
                var attachments by remember { mutableStateOf(emptyList<ChatArtifact>()) }
                var events by remember { mutableStateOf(emptyList<RuntimeEvent>()) }
                LaunchedEffect(panel) { if (panel == "terminal") terminal.open() }
                LaunchedEffect(owner) {
                    while (true) {
                        attachments = withContext(Dispatchers.IO) {
                            ArtifactRepository.importPublished(this@M4ChatActivity)
                            ArtifactRepository.list(this@M4ChatActivity, owner)
                        }
                        events = withContext(Dispatchers.IO) { ChatEventStream.read(this@M4ChatActivity, owner) }
                        delay(1500)
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (panel) {
                            "chat" -> ChatScreen(openTerminal = { panel = "terminal"; terminal.open() },
                                runAgent = { goal -> vm.send(goal) },
                                readAgentProgress = { AgentProgressStore.read(this@M4ChatActivity) },
                                artifacts = attachments, runtimeEvents = events, executeCommand = { command -> terminal.execute(command); panel = "terminal" }, vm = vm)
                            "terminal" -> TerminalPanel(terminal)
                            "files" -> FilesPanel()
                            "tasks" -> SchedulerPanel(owner)
                            "browser" -> BrowserPanel(browser)
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("chat" to "محادثة", "terminal" to "طرفية", "files" to "ملفات", "tasks" to "مهام", "browser" to "متصفح").forEach { (key, label) ->
                            TextButton(onClick = { panel = key; if (key == "terminal") terminal.open() }) {
                                Text((if (panel == key) "● " else "") + label)
                            }
                        }
                    }
                }
            }
        }
    }
    override fun onDestroy() { browser.close(); super.onDestroy() }
}
