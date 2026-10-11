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
import com.musabgpt.m4.scheduler.SchedulerPanel
import com.musabgpt.m4.terminal.EmbeddedAlpineBackend
import com.musabgpt.m4.terminal.TerminalPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Chat, real terminal, files, browser and durable tasks share this Activity. */
class M4ChatActivity : ComponentActivity() {
    private lateinit var terminal: EmbeddedAlpineBackend
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        terminal = EmbeddedAlpineBackend(this, lifecycleScope)
        setContent {
            ChatTheme {
                val vm: ChatViewModel = viewModel()
                val owner by vm.currentId.collectAsState()
                var panel by rememberSaveable { mutableStateOf("chat") }
                var attachments by remember { mutableStateOf(emptyList<ChatArtifact>()) }
                LaunchedEffect(owner) {
                    while (true) {
                        attachments = withContext(Dispatchers.IO) {
                            ArtifactRepository.importPublished(this@M4ChatActivity)
                            ArtifactRepository.list(this@M4ChatActivity, owner)
                        }
                        delay(1500)
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (panel) {
                            "chat" -> ChatScreen(openTerminal = { panel = "terminal"; terminal.open() },
                                runAgent = { goal -> terminal.runAgent(goal, owner); panel = "terminal" },
                                readAgentProgress = { AgentProgressStore.read(this@M4ChatActivity) },
                                artifacts = attachments, executeCommand = { command -> terminal.execute(command); panel = "terminal" }, vm = vm)
                            "terminal" -> TerminalPanel(terminal)
                            "files" -> FilesPanel()
                            "tasks" -> SchedulerPanel(owner)
                            "browser" -> BrowserPanel()
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
    override fun onDestroy() {
        terminal.detach()
        super.onDestroy()
    }
}
