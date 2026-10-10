package com.musabgpt.m4

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import io.musab.arabicterminal.ArabicCommandRouter
import io.musab.arabicterminal.LinuxEnvironment
import io.musab.arabicterminal.PhoneController
import io.musab.arabicterminal.TerminalService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Actual interactive PTY screen owned by TerminalService, embedded in Compose.
 * Dismissing the sheet does NOT terminate the Alpine shell or an ongoing task.
 * The model's tool session is kept separate from this user's interactive PTY.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmbeddedTerminalSheet(
    initialCommand: String?,
    providerFamily: String,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var service by remember { mutableStateOf<TerminalService?>(null) }
    var session by remember { mutableStateOf<TerminalService.Session?>(null) }
    var display by remember { mutableStateOf("جاري تجهيز Alpine Linux...") }
    var notice by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf("") }
    var bound by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? TerminalService.LocalBinder)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
                session = null
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (bound) {
                try { ctx.unbindService(connection) } catch (_: IllegalArgumentException) {}
            }
        }
    }
    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { LinuxEnvironment.install(ctx) }
            coroutineContext.ensureActive()
            val intent = Intent(ctx, TerminalService::class.java)
            ctx.startForegroundService(intent)
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) failure = "تعذر الاتصال بمحرك الطرفية."
        } catch (e: Exception) {
            failure = "تعذر تجهيز Alpine: " + e.message.orEmpty().take(200)
        }
    }
    LaunchedEffect(service) {
        val backend = service ?: return@LaunchedEffect
        try {
            val s = backend.openChatSession()
            session = s
            if (!initialCommand.isNullOrBlank()) {
                if (initialCommand.startsWith("m4-agent-start ")) {
                    // Match MainActivity's private provider setup for the real coding agent.
                    // Never put API tokens, passwords or user-controlled shell text here.
                    withContext(Dispatchers.IO) {
                        val allowed = setOf("freellmpool","gpt4free","free-llm-gateway","free-llm-api")
                        require(providerFamily in allowed) { "مزود غير مدعوم" }
                        val folder = java.io.File(LinuxEnvironment.rootfs(ctx),"root/.m4-agent")
                        check(folder.isDirectory || folder.mkdirs()) { "تعذر إنشاء إعدادات الوكيل" }
                        val file = java.io.File(folder,"provider.env")
                        file.writeText("export M4_GATEWAY_FAMILY=" + providerFamily + "\n")
                        file.setReadable(false,false)
                        file.setWritable(false,false)
                        file.setReadable(true,true)
                        file.setWritable(true,true)
                    }
                }
                backend.write(s, initialCommand + "\n")
            }
            while (coroutineContext.isActive) {
                display = backend.renderChatSession(s).takeLast(30000)
                delay(200)
            }
        } catch (e: Exception) {
            failure = "خطأ في الطرفية: " + e.message.orEmpty().take(160)
        }
    }
    LaunchedEffect(display) {
        scroll.scrollTo(scroll.maxValue)
    }

    fun writeCommand(value: String) {
        val s = session ?: return
        val backend = service ?: return
        if (value.isBlank()) return
        // The SAME Arabic command interpreter used by the original terminal.
        // Android actions execute locally and are not disclosed to a free LLM.
        val parsed = ArabicCommandRouter.parse(value)
        when (parsed.kind) {
            ArabicCommandRouter.Kind.OPEN_APP,
            ArabicCommandRouter.Kind.OPEN_SYSTEM_APP,
            ArabicCommandRouter.Kind.LIST_APPS,
            ArabicCommandRouter.Kind.OPEN_SETTINGS_PAGE,
            ArabicCommandRouter.Kind.OPEN_URL,
            ArabicCommandRouter.Kind.SETTINGS -> {
                notice = PhoneController.run(ctx, parsed)
            }
            ArabicCommandRouter.Kind.SHELL -> backend.write(s, parsed.command + "\n")
            else -> backend.write(s, value + "\n")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.fillMaxHeight(0.92f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Text("الطرفية الفعلية • Alpine PTY", style = MaterialTheme.typography.titleMedium)
            Text("جلسة حيّة داخل المحادثة، لا تطبيق خارجي", style = MaterialTheme.typography.labelSmall)
            if (failure.isNotBlank()) Text(failure, color = MaterialTheme.colorScheme.error)
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF08111D))) {
                Text(
                    display,
                    color = Color(0xFFE7EFF9),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.fillMaxSize()
                        .verticalScroll(scroll)
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("ESC" to "\u001b","TAB" to "\t","Ctrl+C" to "\u0003",
                       "↑" to "\u001b[A","↓" to "\u001b[B","Enter" to "\r").forEach { (key, bytes) ->
                    TextButton(enabled = session != null, onClick = {
                        session?.let { service?.write(it,bytes) }
                    }, contentPadding = PaddingValues(horizontal = 3.dp)) {
                        Text(key, fontSize = 11.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("أمر لينكس أو أمر عربي") },
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    enabled = session != null
                )
                Button(enabled = session != null && input.isNotBlank(), onClick = {
                    writeCommand(input)
                    input = ""
                }) { Text("نفّذ") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
