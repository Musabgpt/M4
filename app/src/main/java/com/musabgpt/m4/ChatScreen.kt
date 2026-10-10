package com.musabgpt.m4

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private val Paper = Color(0xFFF9F8F4)
private val Ink = Color(0xFF1F1E1B)
private val Bubble = Color(0xFFEFEDE6)

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Paper, surface = Paper,
            onSurface = Ink, onBackground = Ink,
            primary = Ink, onPrimary = Paper,
            secondaryContainer = Bubble, onSecondaryContainer = Ink
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    openTerminal: () -> Unit,
    runAgent: (String) -> Unit,
    readAgentProgress: () -> AgentProgress?,
    vm: ChatViewModel = viewModel()
) {
    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val providers by vm.providers.collectAsState()
    val providerIndex by vm.providerIndex.collectAsState()
    val current = conversations.firstOrNull { it.id == currentId } ?: conversations.first()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var editProvider by remember { mutableStateOf(false) }
    var showAgentConfirm by remember { mutableStateOf(false) }
    var showAgentProgress by remember { mutableStateOf(false) }
    var agentProgress by remember { mutableStateOf<AgentProgress?>(null) }
    var endpoint by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }

    fun openSettings() {
        val p = providers[providerIndex]
        endpoint = p.baseUrl
        model = p.model
        apiKey = p.apiKey
        editProvider = true
    }

    if (editProvider) AlertDialog(
        onDismissRequest = { editProvider = false },
        title = { Text("إعداد النموذج") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("البيانات محفوظة محلياً على الهاتف، ولا تُضاف إلى GitHub.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("رابط API") }, singleLine = true)
                OutlinedTextField(value = model, onValueChange = { model = it },
                    label = { Text("معرّف النموذج") }, singleLine = true)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key (اختياري للمجاني)") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.configureProvider(providerIndex, endpoint, model, apiKey)
                editProvider = false
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = { editProvider = false }) { Text("إلغاء") } }
    )

    if (showAgentConfirm) AlertDialog(
        onDismissRequest = { showAgentConfirm = false },
        title = { Text("تشغيل وكيل البرمجة") },
        text = {
            Text("سيتم فتح بيئة Alpine وتشغيل مهمة البرمجة محلياً. ينفّذ الوكيل أوامر المشروع، لذا شغّله للمشاريع التي تثق بها فقط. استدعاء النموذج قد يخضع لحدود مزوده.")
        },
        confirmButton = {
            TextButton(onClick = {
                showAgentConfirm = false
                val goal = input.trim()
                if (goal.isNotEmpty()) {
                    runAgent(goal)
                    input = ""
                }
            }) { Text("تشغيل") }
        },
        dismissButton = { TextButton(onClick = { showAgentConfirm = false }) { Text("إلغاء") } }
    )

    if (showAgentProgress) AlertDialog(
        onDismissRequest = { showAgentProgress = false },
        title = { Text("حالة وكيل البرمجة") },
        text = {
            val p = agentProgress
            if (p == null) {
                Text("لا توجد مهمة محفوظة بعد. ابدأ مهمة عبر «تنفيذ كود».")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(p.displayPhase, style = MaterialTheme.typography.titleMedium)
                    Text("الخطوات المسجلة: " + p.steps)
                    if (p.goal.isNotBlank()) Text("الهدف: " + p.goal)
                    if (p.summary.isNotBlank()) Text("النتيجة: " + p.summary)
                    Text("حالة محفوظة محلياً. اختبار بناء APK يتم في GitHub Actions.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { agentProgress = readAgentProgress() }) {
                    Text("تحديث")
                }
                if (agentProgress?.canResume == true) {
                    TextButton(onClick = {
                        val goal = agentProgress?.goal.orEmpty()
                        showAgentProgress = false
                        if (goal.isNotBlank()) runAgent(goal)
                    }) { Text("استئناف") }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { showAgentProgress = false }) { Text("إغلاق") }
        }
    )

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                TextButton(onClick = {
                    vm.newChat()
                    scope.launch { drawer.close() }
                }, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("محادثة جديدة")
                }
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1) },
                            selected = c.id == currentId,
                            onClick = {
                                vm.select(c.id)
                                scope.launch { drawer.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                HorizontalDivider()
                TextButton(onClick = {
                    scope.launch { drawer.close() }
                    openTerminal()
                }, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Filled.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("بيئة Linux الكاملة")
                }
                Text("M4 • MusabAI + Term", modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text(providers[providerIndex].name)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                providers.forEachIndexed { index, p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            vm.selectProvider(index)
                                            menuOpen = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("إعدادات النموذج") },
                                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                    onClick = { menuOpen = false; openSettings() }
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "القائمة")
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            agentProgress = readAgentProgress()
                            showAgentProgress = true
                        }) {
                            Icon(Icons.Filled.Info, contentDescription = "حالة الوكيل")
                        }
                        IconButton(onClick = openTerminal) {
                            Icon(Icons.Filled.Terminal, contentDescription = "الطرفية")
                        }
                        IconButton(onClick = { vm.newChat() }) {
                            Icon(Icons.Filled.Add, contentDescription = "محادثة جديدة")
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("السحابة للمحادثة · الوكيل البرمجي يعمل داخل Linux",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF6A665E),
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (input.isNotBlank()) showAgentConfirm = true },
                            enabled = input.isNotBlank() && !streaming) {
                            Icon(Icons.Filled.Code, contentDescription = null,
                                modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("تنفيذ كود")
                        }
                    }
                    InputBar(
                        text = input,
                        onTextChange = { input = it },
                        streaming = streaming,
                        onSend = {
                            if (input.isNotBlank()) {
                                vm.send(input)
                                input = ""
                            }
                        },
                        onStop = vm::stop
                    )
                }
            }
        ) { padding ->
            if (current.messages.isEmpty()) {
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("بماذا أساعدك اليوم؟",
                            style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(10.dp))
                        Text("محادثة ذكاء اصطناعي · تطوير وبرمجة على الهاتف",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF78746D))
                    }
                }
            } else {
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(current.messages.asReversed(), key = { it.id }) { message ->
                        MessageRow(message)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: UiMessage) {
    if (msg.role == "user") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 320.dp)) {
                Text(msg.content, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            }
        }
    } else {
        Text(
            msg.content.ifEmpty { "…" },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InputBar(
    text: String, onTextChange: (String) -> Unit,
    streaming: Boolean, onSend: () -> Unit, onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("اكتب رسالتك...") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 6
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = if (streaming) onStop else onSend,
            enabled = streaming || text.isNotBlank()) {
            if (streaming) {
                Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.onPrimary,
                    RoundedCornerShape(3.dp)))
            } else {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "إرسال")
            }
        }
    }
}
