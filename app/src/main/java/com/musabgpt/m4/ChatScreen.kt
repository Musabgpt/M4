package com.musabgpt.m4

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

private val Clay = Color(0xFFD97757)

/** Warm paper palette in light mode, warm charcoal in dark mode (follows the system). */
@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            background = Color(0xFF262624),
            onBackground = Color(0xFFF5F4EE),
            surface = Color(0xFF30302E),
            onSurface = Color(0xFFF5F4EE),
            surfaceVariant = Color(0xFF1F1E1D),
            onSurfaceVariant = Color(0xFFA3A199),
            outline = Color(0xFF4A4945),
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
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun greeting(): String {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (hour in 5..11) "صباح الخير" else "مساء الخير"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    readAgentProgress: () -> AgentProgress?,
    vm: ChatViewModel = viewModel()
) {
    val conversations by vm.conversations.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val isStreaming by vm.streaming.collectAsState()
    val providers by vm.providers.collectAsState()
    val providerIndex by vm.providerIndex.collectAsState()
    val activeRoute by vm.activeRoute.collectAsState()
    val terminalOutput by vm.terminalOutput.collectAsState()
    val approval by vm.approval.collectAsState()
    val contactPermissionTicket by vm.contactsPermissionTicket.collectAsState()
    val contactPermissionLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> vm.onContactsPermissionResult(granted) }
    LaunchedEffect(contactPermissionTicket) {
        if(contactPermissionTicket>0) contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }
    val provider = providers[providerIndex.coerceIn(providers.indices)]
    val current = conversations.firstOrNull { it.id == currentId } ?: conversations.first()

    val colors = MaterialTheme.colorScheme
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showAgentConfirm by remember { mutableStateOf(false) }
    var showAgentProgress by remember { mutableStateOf(false) }
    var agentProgress by remember { mutableStateOf<AgentProgress?>(null) }
    val backgroundTaskRunning by vm.backgroundTaskRunning.collectAsState()
    fun launchTerminal(command: String? = null) {
        vm.launchTerminal(command)
    }

    if (approval != null) {
        val pending=approval!!
        AlertDialog(
            onDismissRequest = { vm.decideTool(false) },
            title = { Text("موافقة على أمر الطرفية العربية") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("النموذج يطلب تشغيل أمر حقيقي في Alpine داخل التطبيق. راجع الأمر قبل السماح؛ قد تُرسل نتيجة تنفيذه إلى مزود النموذج المجاني. لا تسمح بأوامر تكشف كلمات المرور أو الرموز.")
                    Surface(
                        color = colors.surfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(pending.command.take(2000), fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { vm.decideTool(true) }) { Text("السماح لهذا الأمر") }
                    TextButton(onClick = { vm.decideTool(true, approveTask = true) }) {
                        Text("السماح لهذه المهمة (حتى 12 أمراً)")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.decideTool(false) }) { Text("رفض") }
            }
        )
    }
    if (showAgentConfirm) {
        AlertDialog(
            onDismissRequest = { showAgentConfirm = false },
            title = { Text("تنفيذ المهمة في الطرفية العربية") },
            text = { Text("سيعمل وكيل M4 على ملفات المشروع باستخدام Alpine المدمجة. قد ينفذ أوامر تغيّر الملفات؛ راجع المشروع واحفظ نسخك المهمة.") },
            confirmButton = {
                TextButton(onClick = {
                    showAgentConfirm = false
                    if (input.isNotBlank()) {
                        launchTerminal("m4-agent-start " + shellQuoteGoal(input.trim()))
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
                            if (goal.isNotBlank()) launchTerminal("m4-agent-start " + shellQuoteGoal(goal))
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
                    "المحادثات",
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
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(conversations, key = { it.id }) { conv ->
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
                    launchTerminal()
                }, modifier = Modifier.padding(12.dp)) {
                    Icon(Icons.Filled.Terminal, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("الطرفية العربية المدمجة")
                }
                TextButton(onClick = {
                    scope.launch { drawerState.close() }
                    launchTerminal("gh auth login --hostname github.com --git-protocol https --web")
                }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Icon(Icons.Filled.Link, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ربط GitHub رسمياً")
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
                        IconButton(onClick = {
                            agentProgress = readAgentProgress()
                            showAgentProgress = true
                        }) {
                            Icon(Icons.Filled.Info, contentDescription = "حالة الوكيل")
                        }
                        IconButton(onClick = vm::newChat) {
                            Icon(Icons.Filled.Add, contentDescription = "محادثة جديدة")
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    if (backgroundTaskRunning) {
                        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),
                            verticalAlignment=Alignment.CenterVertically) {
                            Text("Alpine يعمل في هذه المحادثة",
                                modifier=Modifier.weight(1f), color=colors.onSurfaceVariant)
                            TextButton(onClick={vm.stopBackgroundTask()}) { Text("إيقاف") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            activeRoute.ifBlank { "مزودات مجانية • لا مفاتيح يدوية" },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(
                            onClick = { showAgentConfirm = true },
                            enabled = input.isNotBlank() && !isStreaming
                        ) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تنفيذ كود")
                        }
                    }
                    if (terminalOutput.isNotBlank() && isStreaming) {
                        val logScroll=rememberScrollState()
                        LaunchedEffect(terminalOutput) { logScroll.scrollTo(logScroll.maxValue) }
                        Surface(
                            color = Color(0xFF08111D),
                            modifier = Modifier.fillMaxWidth().heightIn(max=152.dp)
                                .padding(horizontal=12.dp),
                            shape = RoundedCornerShape(9.dp)
                        ) {
                            Text(
                                terminalOutput,
                                color=Color(0xFFE7EFF9),
                                fontFamily=FontFamily.Monospace,
                                fontSize=11.sp,
                                modifier=Modifier.verticalScroll(logScroll).padding(10.dp)
                            )
                        }
                    }
                    InputBar(
                        text = input,
                        onTextChange = { input = it },
                        hint = "اكتب رسالتك (أو ! ثم أمر Linux)...",
                        isStreaming = isStreaming,
                        onSend = {
                            vm.send(input)
                            input = ""
                        },
                        onStop = vm::stop
                    )
                }
            }
        ) { padding ->
            if (current.messages.isEmpty()) {
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
                        "كيف أقدر أساعدك اليوم؟",
                        fontFamily = FontFamily.Serif,
                        fontSize = 20.sp,
                        color = colors.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val lastId = current.messages.last().id
                LazyColumn(
                    reverseLayout = true,
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(current.messages.reversed(), key = { it.id }) { msg ->
                        MessageRow(
                            msg = msg,
                            showActions = !(isStreaming && msg.id == lastId)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageRow(msg: UiMessage, showActions: Boolean) {
    val colors = MaterialTheme.colorScheme

    if (msg.role == "user") {
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
        return
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (msg.content.isEmpty()) {
            ThinkingDot()
        } else {
            parseBlocks(msg.content).forEach { block ->
                when (block) {
                    is Block.Text -> Text(
                        text = inline(block.value, colors.surfaceVariant),
                        fontFamily = FontFamily.Serif,
                        fontSize = 17.sp,
                        lineHeight = 27.sp,
                        color = colors.onBackground
                    )
                    is Block.Code -> CodeBlock(block.lang, block.value)
                }
            }
            if (showActions) CopyAction(msg.content)
        }
    }
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
private fun CodeBlock(lang: String, code: String) {
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
            // Code always reads left to right, even inside an RTL screen
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Text(
                    text = code,
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
    hint: String,
    isStreaming: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
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
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    maxLines = 6,
                    textStyle = TextStyle(color = colors.onSurface, fontSize = 16.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(Clay),
                    modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                    decorationBox = { inner ->
                        Box {
                            if (text.isEmpty()) {
                                Text(hint, fontSize = 16.sp, color = colors.onSurfaceVariant)
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
/** Avoid shell injection when passing user text to the bundled M4 coding agent. */
private fun shellQuoteGoal(value: String): String = "'" + value.replace("'", "'\\''") + "'"
