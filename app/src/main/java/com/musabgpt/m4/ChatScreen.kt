package com.musabgpt.m4

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import com.musabgpt.m4.terminal.TerminalPanel
import com.musabgpt.m4.artifacts.ArtifactChatCard
import com.musabgpt.m4.artifacts.ChatArtifact
import com.musabgpt.m4.runtime.RuntimeEvent
import com.musabgpt.m4.runtime.InteractiveToolCard
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private val Clay = Color(0xFFD97757)

/** Warm paper palette in light mode, warm charcoal in dark mode (follows the system). */
@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            background = Color(0xFF101010),
            onBackground = Color(0xFFF5F4EE),
            surface = Color(0xFF232323),
            onSurface = Color(0xFFF5F4EE),
            surfaceVariant = Color(0xFF292929),
            onSurfaceVariant = Color(0xFFA3A199),
            outline = Color(0xFF3A3A3A),
            primary = Clay,
            onPrimary = Color.White
        )
    } else {
        lightColorScheme(
            background = Color(0xFFFAF9F5),
            onBackground = Color(0xFF29261B),
            surface = Color.White,
            onSurface = Color(0xFF29261B),
            surfaceVariant = Color(0xFFF0EEE6),
            onSurfaceVariant = Color(0xFF83827C),
            outline = Color(0xFFE3E0D6),
            primary = Clay,
            onPrimary = Color.White
        )
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (hour in 5..11) "صباح الخير" else "مساء الخير"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    openTerminal: () -> Unit,
    openPanel: (String) -> Unit = {},
    runAgent: (String) -> Unit,
    readAgentProgress: () -> AgentProgress?,
    artifacts: List<ChatArtifact> = emptyList(),
    runtimeEvents: List<RuntimeEvent> = emptyList(),
    executeCommand: (String) -> Unit = {},
    loadOlderEvents: () -> Unit = {},
    vm: ChatViewModel = viewModel()
) {
    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val isStreaming by vm.streaming.collectAsState()
    val providers by vm.providers.collectAsState()
    val providerIndex by vm.providerIndex.collectAsState()
    val activeRoute by vm.activeRoute.collectAsState()
    val permissionMode by vm.permissionMode.collectAsState()
    val provider = providers[providerIndex.coerceIn(providers.indices)]
    val current = conversations.firstOrNull { it.id == currentId } ?: conversations.first()

    val colors = MaterialTheme.colorScheme
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var terminalExpanded by rememberSaveable { mutableStateOf(false) }
    var addSheet by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showAgentConfirm by remember { mutableStateOf(false) }
    var showAgentProgress by remember { mutableStateOf(false) }
    var agentProgress by remember { mutableStateOf<AgentProgress?>(null) }
    var search by remember { mutableStateOf("") }
    var permissionsOpen by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var steering by remember { mutableStateOf(false) }
    var steerText by remember { mutableStateOf("") }
    val pendingAttachments by vm.pendingAttachments.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(vm::attach) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap -> bitmap?.let(vm::attachCamera) }
    fun toggleTerminal() {
        terminalExpanded = !terminalExpanded
        if (terminalExpanded) openTerminal()
    }
    if (addSheet) {
        ModalBottomSheet(onDismissRequest = { addSheet = false }, containerColor = colors.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("إضافة إلى المحادثة", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = { addSheet = false; picker.launch(arrayOf("*/*")) }) { Text("الملفات") }
                    TextButton(onClick = { addSheet = false; imagePicker.launch("image/*") }) { Text("الصور") }
                    TextButton(onClick = { addSheet = false; camera.launch(null) }) { Text("الكاميرا") }
                }
                HorizontalDivider()
                TextButton(onClick = { addSheet = false; openPanel("browser") }) { Text("البحث والمتصفح") }
                TextButton(onClick = { addSheet = false; toggleTerminal() }, modifier = Modifier.testTag("chat-terminal-toggle")) { Text("الطرفية داخل المحادثة") }
                TextButton(onClick = { addSheet = false; openPanel("tools") }) { Text("المهارات والإضافات") }
                TextButton(onClick = { addSheet = false; openPanel("tasks") }) { Text("المهام المجدولة") }
                TextButton(onClick = { addSheet = false; showAgentConfirm = true }, enabled = input.isNotBlank() && !isStreaming) { Text("تنفيذ المشروع باستخدام الأدوات") }
                TextButton(onClick = { addSheet = false; permissionsOpen = true }) { Text("صلاحيات الوكيل") }
            }
        }
    }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("اسم المحادثة") },
        text = { OutlinedTextField(title, { title = it }) },
        confirmButton = { TextButton(onClick = { vm.rename(currentId, title); rename = false }) { Text("حفظ") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("إلغاء") } })
    deleteId?.let { id -> AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("حذف المحادثة من السجل؟") },
        text = { Text("ستُحذف رسائل هذه المحادثة. المرفقات المحفوظة تظل متاحة في ملفات التطبيق.") },
        confirmButton = { TextButton(onClick = { vm.delete(id); deleteId = null }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { deleteId = null }) { Text("إلغاء") } }) }
    if (steering) AlertDialog(onDismissRequest = { steering = false }, title = { Text("توجيه المهمة الحالية") },
        text = { OutlinedTextField(steerText, { steerText = it }, label = { Text("تعديل الهدف أو إضافة معلومة") }) },
        confirmButton = { TextButton(onClick = { vm.steer(steerText); steering = false; steerText = "" }) { Text("إرسال التوجيه") } }, dismissButton = { TextButton(onClick = { steering = false }) { Text("إلغاء") } })


    if (showAgentConfirm) {
        AlertDialog(
            onDismissRequest = { showAgentConfirm = false },
            title = { Text("تنفيذ المهمة في الطرفية العربية") },
            text = { Text(if (permissionMode == "plan_only") "سيقرأ وكيل M4 المشروع ويعد خطة؛ أدوات التعديل والتنفيذ محظورة في هذا الوضع."
                else "سيعمل وكيل M4 على ملفات المشروع باستخدام Alpine المدمجة. قد ينفذ أوامر تغيّر الملفات؛ راجع المشروع واحفظ نسخك المهمة.") },
            confirmButton = {
                TextButton(onClick = {
                    showAgentConfirm = false
                    if (input.isNotBlank()) {
                        runAgent(input.trim())
                        input = ""
                    }
                }) { Text("تشغيل") }
            },
            dismissButton = {
                TextButton(onClick = { showAgentConfirm = false }) { Text("إلغاء") }
            }
        )
    }
    if (showAgentProgress) {
        AlertDialog(
            onDismissRequest = { showAgentProgress = false },
            title = { Text("حالة وكيل M4") },
            text = {
                val a = agentProgress
                if (a == null) Text("لا توجد مهمة محفوظة حتى الآن.")
                else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(a.displayPhase)
                    Text("الخطوات: " + a.steps)
                    if (a.goal.isNotBlank()) Text(a.goal)
                    if (a.summary.isNotBlank()) Text(a.summary)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { agentProgress = readAgentProgress() }) { Text("تحديث") }
                    if (agentProgress?.canResume == true) {
                        TextButton(onClick = {
                            val goal = agentProgress?.goal.orEmpty()
                            showAgentProgress = false
                            if (goal.isNotBlank()) runAgent(goal)
                        }) { Text("استئناف") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { showAgentProgress = false }) { Text("إغلاق") } }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = colors.background) {
                Text(
                    "M4 — MusabAI",
                    fontFamily = FontFamily.Serif,
                    fontSize = 22.sp,
                    color = colors.onBackground,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
                )
                TextButton(
                    onClick = {
                        vm.newChat()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) { Text("+ محادثة جديدة", color = Clay) }
                val links = listOf(
                    "المحادثات" to "chat",
                    "المشاريع" to "github",
                    "الكود والطرفية" to "terminal",
                    "الملفات والمرفقات" to "files",
                    "المهارات والإضافات" to "tools",
                    "المهام" to "tasks",
                    "المتصفح" to "browser"
                )
                links.forEach { (label, target) ->
                    NavigationDrawerItem(
                        label = { Text(label) },
                        selected = false,
                        onClick = {
                            scope.launch { drawerState.close() }
                            if (target == "terminal") toggleTerminal()
                            else if (target != "chat") openPanel(target)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                HorizontalDivider()
                Text("الأخيرة", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = colors.onSurfaceVariant)
                OutlinedTextField(search, { search = it }, label = { Text("البحث في المحادثات") }, modifier = Modifier.padding(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { title = current.title; rename = true }, enabled = !isStreaming) { Text("تسمية") }
                    TextButton(onClick = { vm.fork() }, enabled = !isStreaming) { Text("فرع") }
                    TextButton(onClick = { vm.exportChat() }) { Text("تصدير") }
                    TextButton(onClick = { deleteId = currentId }, enabled = !isStreaming) { Text("حذف") }
                }
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(conversations.filter { search.isBlank() || it.title.contains(search, true) || it.messages.any { m -> m.content.contains(search, true) } }, key = { it.id }) { conv ->
                        NavigationDrawerItem(
                            label = { Text(conv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = conv.id == currentId,
                            onClick = {
                                vm.select(conv.id)
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = colors.surfaceVariant,
                                unselectedContainerColor = Color.Transparent,
                                selectedTextColor = colors.onBackground,
                                unselectedTextColor = colors.onBackground
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
                HorizontalDivider()
                TextButton(onClick = {
                    scope.launch { drawerState.close() }
                    toggleTerminal()
                }, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Filled.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("الطرفية العربية المدمجة")
                }
            }
        }
    ) {
        Scaffold(
            containerColor = colors.background,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = colors.background
                    ),
                    title = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { menuOpen = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(provider.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(" ▾", fontSize = 14.sp, color = colors.onSurfaceVariant)
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
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "القائمة")
                        }
                    },
                    actions = {
                        IconButton(onClick = vm::newChat) {
                            Icon(Icons.Filled.Add, contentDescription = "محادثة جديدة")
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    if (isStreaming) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = vm::pause) { Text("إيقاف مؤقت") }
                        TextButton(onClick = vm::resume) { Text("متابعة") }
                        TextButton(onClick = { steering = true }) { Text("توجيه") }
                    }
                    if (pendingAttachments.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        pendingAttachments.forEach { attachment -> AssistChip(onClick = { vm.removeAttachment(attachment.id) }, label = { Text("${attachment.name} ×") }) }
                    }
                    if (input.startsWith("/") && !input.contains(' ')) Row(Modifier.horizontalScroll(rememberScrollState())) {
                        listOf("/tools", "/skills", "/mcp", "/agents", "/hooks", "/terminal", "/termux", "/phone", "/browser", "/artifacts", "/export", "/schedule", "/tasks", "/doctor", "/selftest", "/github", "/permissions", "/rewind", "/cost", "/compact", "/memory", "/init", "/git", "/model", "/status").filter { it.startsWith(input) }.take(12).forEach { suggestion -> TextButton(onClick = { input = suggestion + " " }) { Text(suggestion) } }
                    }
                    Box {
                        DropdownMenu(permissionsOpen, { permissionsOpen = false }) {
                            listOf("auto_project" to "تنفيذ داخل المشروع", "accept_edits" to "تعديل الملفات، اسأل قبل التشغيل", "ask_always" to "اسأل قبل كل تعديل وتشغيل", "plan_only" to "تخطيط للقراءة فقط").forEach { (mode, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { vm.setPermissionMode(mode); permissionsOpen = false }) }
                        }
                    }
                    if (terminalExpanded) {
                        Surface(color = colors.surfaceVariant, modifier = Modifier.fillMaxWidth().height(275.dp).testTag("inline-terminal")) {
                            TerminalPanel(vm.terminal)
                        }
                    }
                    InputBar(
                        text = input,
                        onTextChange = { input = it },
                        isStreaming = isStreaming,
                        onSend = {
                            if (input.trim().startsWith("!")) {
                                vm.recordTerminalRequest(input.trim())
                                executeCommand(input.trim().drop(1))
                                terminalExpanded = true
                                openTerminal()
                            } else vm.send(input)
                            input = ""
                        },
                        onStop = vm::stop,
                        onAttach = { addSheet = true }
                    )
                }
            }
        ) { padding ->
            if (current.messages.isEmpty() && artifacts.isEmpty() && runtimeEvents.isEmpty()) {
                Column(
                    modifier = Modifier.padding(padding).fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        greeting(),
                        fontFamily = FontFamily.Serif,
                        fontSize = 34.sp,
                        color = colors.onBackground,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "كيف أقدر أنفذ مشروعك اليوم؟",
                        fontFamily = FontFamily.Serif,
                        fontSize = 20.sp,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val lastId = current.messages.lastOrNull()?.id
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(artifacts.reversed(), key = { "artifact-" + it.id }) { artifact -> ArtifactChatCard(artifact) }
                    if (runtimeEvents.size >= 100) item { TextButton(onClick = loadOlderEvents) { Text("تحميل سجل أقدم") } }
                    items(runtimeEvents.filter { it.kind in setOf("tool_started", "tool_finished", "turn_finished", "notice", "hook_error", "provider_fallback", "provider_route") }.groupBy { if (it.callId.isNotBlank()) "call-${it.turn}-${it.callId}" else "event-${it.id}" }.values.map { it.last() }.sortedByDescending { it.time }, key = { "event-" + it.id }) { event -> InteractiveToolCard(event) }
                    items(current.messages.reversed(), key = { it.id }) { msg ->
                        MessageRow(
                            msg = msg,
                            showActions = !(isStreaming && msg.id == lastId), vm = vm
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: UiMessage, showActions: Boolean, vm: ChatViewModel) {
    val colors = MaterialTheme.colorScheme

    if (msg.role == "user") {
        Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.surfaceVariant,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = msg.content,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = colors.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        if (showActions) MessageActions(msg, vm)
        }
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (msg.content.isEmpty()) {
            ThinkingDot()
        } else {
            parseBlocks(msg.content).forEach { block ->
                when (block) {
                    is Block.Text -> ChatMarkdown(block.value)
                    is Block.Code -> CodeBlock(block.lang, block.value, vm)
                }
            }
            if (showActions) MessageActions(msg, vm)
        }
    }
}

@Composable
private fun MessageActions(msg: UiMessage, vm: ChatViewModel) {
    var editing by remember { mutableStateOf(false) }
    var edited by remember(msg.id) { mutableStateOf(msg.content) }
    var rewind by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        CopyAction(msg.content)
        Box {
            TextButton(onClick = { menu = true }) { Text("خيارات") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("فرع من هنا") }, onClick = { menu = false; vm.fork(msg.id) })
                if (msg.role == "user") DropdownMenuItem(text = { Text("تعديل وإعادة الإرسال") }, onClick = { menu = false; editing = true })
                if (msg.turnId.isNotBlank()) DropdownMenuItem(text = { Text("استرجاع الملفات والمحادثة") }, onClick = { menu = false; rewind = true })
            }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false }, title = { Text("تعديل الرسالة في فرع جديد") }, text = { OutlinedTextField(edited, { edited = it }, maxLines = 8) }, confirmButton = { TextButton(onClick = { vm.fork(msg.id, edited); editing = false }) { Text("إعادة الإرسال") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("إلغاء") } })
    if (rewind) AlertDialog(onDismissRequest = { rewind = false }, title = { Text("استرجاع الملفات والمحادثة") }, text = { Text("سيُسترجع محتوى الملفات قبل هذه الخطوة وتُلغى تعديلات خطواتك اللاحقة، ثم تُحذف رسائلها وما بعدها من المحادثة. يتوقف التراجع إذا تعارض ملف مع تعديل خارجي أو محادثة أخرى. الإجراءات خارج الملفات، مثل إرسال رسالة، لا يمكن استرجاعها.") }, confirmButton = { TextButton(onClick = { vm.rewind(msg.turnId); rewind = false }) { Text("استرجاع") } }, dismissButton = { TextButton(onClick = { rewind = false }) { Text("إلغاء") } })
}

@Composable
private fun CopyAction(text: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Text(
        text = if (copied) "تم النسخ" else "نسخ",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

@Composable
private fun CodeBlock(lang: String, code: String, vm: ChatViewModel) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceVariant,
        border = BorderStroke(1.dp, colors.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    lang.ifEmpty { "code" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant
                )
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                }) {
                    Text(
                        if (copied) "تم النسخ" else "نسخ",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Row {
                TextButton(onClick = { vm.saveCode(lang, code) }) { Text("حفظ كملف") }
                if (lang.lowercase() in setOf("py", "python", "bash", "sh", "shell")) TextButton(onClick = { vm.send("أنشئ ملفًا لهذا الكود ثم شغله واذكر نتيجة التشغيل الفعلية:\n```$lang\n$code\n```") }) { Text("تشغيل") }
            }
            // Code always reads left to right, even inside an RTL screen
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = highlightedCode(code),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = colors.onBackground,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ThinkingDot() {
    val transition = rememberInfiniteTransition(label = "thinking")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "thinking-alpha"
    )
    Box(Modifier.size(12.dp).alpha(alpha).background(Clay, CircleShape))
}

@Composable
private fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val canSend = isStreaming || text.isNotBlank()

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                TextButton(onClick = onAttach, enabled = !isStreaming, modifier = Modifier.testTag("chat-plus"), contentPadding = PaddingValues(4.dp)) { Text("+", style = MaterialTheme.typography.titleLarge) }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    maxLines = 6,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Clay),
                    modifier = Modifier.weight(1f).padding(vertical = 7.dp).testTag("chat-input"),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text("تحدث مع M4...", fontSize = 16.sp, color = colors.onSurfaceVariant)
                            }
                            inner()
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (canSend) Clay else Clay.copy(alpha = 0.35f))
                        .testTag("chat-send")
                        .clickable(enabled = canSend) { if (isStreaming) onStop() else onSend() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isStreaming) {
                        Box(Modifier.size(12.dp).background(Color.White, RoundedCornerShape(3.dp)))
                    } else {
                        Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Text(
            "قد يخطئ الذكاء الاصطناعي. راجع الإجابات المهمة.",
            fontSize = 11.sp,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
    }
}

private sealed interface Block {
    data class Text(val value: String) : Block
    data class Code(val lang: String, val value: String) : Block
}

/** Splits a reply on ``` fences. An unclosed fence while streaming renders as code right away. */
private fun parseBlocks(source: String): List<Block> {
    val out = mutableListOf<Block>()
    source.split("```").forEachIndexed { i, part ->
        if (i % 2 == 0) {
            if (part.isNotBlank()) out += Block.Text(part.trim())
        } else {
            val nl = part.indexOf('\n')
            val lang = if (nl >= 0) part.substring(0, nl).trim() else ""
            val code = if (nl >= 0) part.substring(nl + 1) else part
            out += Block.Code(lang, code.trimEnd())
        }
    }
    return out
}

/** Minimal inline markdown: **bold** and `code`. */
private fun inline(text: String, codeBg: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        if (text.startsWith("**", i)) {
            val end = text.indexOf("**", i + 2)
            if (end > 0) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > 0) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
