package com.musabgpt.m4.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.musabgpt.m4.artifacts.ArtifactRepository
import io.musab.arabicterminal.LinuxEnvironment
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class EditorDocument(val file: File, initial: String, initialHash: String) {
    var content by mutableStateOf(initial)
    var original by mutableStateOf(initial)
    var hash by mutableStateOf(initialHash)
}

class WorkspaceEditorState : androidx.lifecycle.ViewModel() {
    val documents = mutableStateListOf<EditorDocument>()
    var active by mutableStateOf<EditorDocument?>(null)
    var ready by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set
    var setupError by mutableStateOf<String?>(null)
        private set
    fun prepare(context: android.content.Context) {
        if (ready || preparing) return
        preparing = true
        setupError = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    LinuxEnvironment.install(context.applicationContext)
                    io.musab.arabicterminal.ProjectBootstrap.ensure(LinuxEnvironment.rootfs(context))
                }
                ready = true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) { setupError = failure.message ?: "Workspace setup failed" }
            finally { preparing = false }
        }
    }
}

/** Bounded project editor with a content-hash conflict check and private backups. */
@Composable
fun FilesPanel(state: WorkspaceEditorState = androidx.lifecycle.viewmodel.compose.viewModel(), askAgent: (String) -> Unit = {}) {
    val context = LocalContext.current
    val chatVm: com.musabgpt.m4.ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val providerIndex by chatVm.providerIndex.collectAsState()
    val providers by chatVm.providers.collectAsState()
    val scope = rememberCoroutineScope()
    val root = remember { File(LinuxEnvironment.rootfs(context), "root/projects/M4") }
    var directory by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf(emptyList<File>()) }
    val document = state.active
    val selected = document?.file
    val content = document?.content.orEmpty()
    val original = document?.original.orEmpty()
    val hash = document?.hash.orEmpty()
    var error by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf(false) }
    var instruction by remember { mutableStateOf("") }
    var inlineEdit by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var filename by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }
    fun allowed(file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical.toPath().startsWith(root.canonicalFile.toPath()) &&
            canonical.name !in setOf(".git", ".term-agent", ".env", ".ssh", ".aws") &&
            !canonical.name.endsWith(".pem") && !canonical.name.endsWith(".key")
    }
    LaunchedEffect(Unit) { state.prepare(context) }
    if (!state.ready) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("M4 Workspace", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            if (state.setupError == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("جارٍ تجهيز ملفات المشروع وأدواته لأول تشغيل…")
            } else {
                Text(state.setupError.orEmpty(), color = MaterialTheme.colorScheme.error)
                Button(onClick = { state.prepare(context) }) { Text("إعادة المحاولة") }
            }
        }
        return
    }
    LaunchedEffect(directory, state.ready, revision) {
        entries = withContext(Dispatchers.IO) { directory.listFiles().orEmpty().filter { allowed(it) }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }).take(250) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp).testTag("workspace-ready")) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            TextButton(onClick = { state.active = null }) { Text("Explorer") }
            state.documents.forEach { tab ->
                TextButton(onClick = { state.active = tab }) {
                    Text((if (state.active == tab) "▎" else "") + tab.file.name + if (tab.content != tab.original) " ●" else "")
                }
            }
        }
        Text(directory.toRelativeString(root).ifBlank { "/root/projects/M4" }, style = MaterialTheme.typography.labelSmall)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (selected == null) {
            if (directory != root) TextButton(onClick = { directory = directory.parentFile ?: root }) { Text("المجلد الأعلى") }

            TextButton(onClick = { creating = true }) { Text("New file") }
            OutlinedTextField(filter, { filter = it }, singleLine = true, placeholder = { Text("Filter files") }, modifier = Modifier.fillMaxWidth())
            LazyColumn {
                items(entries.filter { it.name.contains(filter, true) }, key = { it.absolutePath }) { file ->
                    TextButton(onClick = {
                        if (file.isDirectory) directory = file
                        else scope.launch {
                            try {
                                val text = withContext(Dispatchers.IO) {
                                    require(allowed(file) && file.length() <= 250000)
                                    val bytes = file.readBytes(); require(bytes.none { it == 0.toByte() })
                                    bytes.toString(Charsets.UTF_8) to java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                                }
                                val existing = state.documents.firstOrNull { it.file == file }
                                val opened = existing ?: EditorDocument(file, text.first, text.second).also { state.documents.add(it) }
                                state.active = opened; error = ""
                            } catch (e: Exception) { error = "تعذرت قراءة الملف: ${e.message}" }
                        }
                    }) { Text((if (file.isDirectory) "▸ " else "") + file.name) }
                }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { state.active = null }) { Text("رجوع") }
                TextButton(onClick = { diff = true }, enabled = original != content) { Text("Review") }
                TextButton(onClick = { ask = true }) { Text("Ask Agent") }
                TextButton(onClick = { inlineEdit = true }) { Text("Edit with AI") }
            }
            Row(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text((1..(content.count { it == '\n' } + 1)).joinToString("\n"),
                    modifier = Modifier.padding(top = 16.dp, end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(content, { document?.content = it },
                    visualTransformation = CodeHighlight,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                    modifier = Modifier.testTag("code-editor").width(maxOf(700, (content.lineSequence().maxOfOrNull { it.length } ?: 0).coerceAtMost(500) * 9).dp),
                    placeholder = { Text("Write code…") })
            }
            Row {
                TextButton(onClick = { document?.content = original }, enabled = original != content) { Text("Discard changes") }
                TextButton(onClick = { state.documents.remove(document); state.active = state.documents.lastOrNull() }, enabled = original == content) { Text("Close tab") }
            }
        }
    }
    if (inlineEdit && document != null) InlineEditDialog(document, providers[providerIndex.coerceIn(providers.indices)]) { inlineEdit = false }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New file") },
        text = { OutlinedTextField(filename, { filename = it }, singleLine = true, label = { Text("Filename") }) },
        confirmButton = { TextButton(enabled = filename.isNotBlank(), onClick = { scope.launch {
            try {
                val target = withContext(Dispatchers.IO) {
                    require(filename !in setOf(".", "..") && !filename.contains('/') && !filename.contains('\\')) { "استخدم اسم ملف فقط" }
                    val file = File(directory, filename)
                    require(allowed(file)) { "المسار غير مسموح" }
                    require(file.createNewFile()) { "الملف موجود بالفعل" }
                    file
                }
                val opened = EditorDocument(target, "", withContext(Dispatchers.IO) { ArtifactRepository.hash(target) })
                state.documents.add(opened); state.active = opened
                revision++; creating = false; filename = ""; error = ""
            } catch (failure: Exception) { error = failure.message.orEmpty(); creating = false }
        } }) { Text("Create") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    if (ask) AlertDialog(onDismissRequest = { ask = false }, title = { Text("Ask about ${selected?.name.orEmpty()}") },
        text = { OutlinedTextField(instruction, { instruction = it }, label = { Text("Instruction") }) },
        confirmButton = { TextButton(enabled = instruction.isNotBlank(), onClick = {
            askAgent("$instruction\n\nFile: ${selected?.toRelativeString(root)}\nCurrent editor buffer (may contain unsaved edits):\n```\n$content\n```")
            ask = false; instruction = ""
        }) { Text("Send with file context") } }, dismissButton = { TextButton(onClick = { ask = false }) { Text("Cancel") } })
    if (diff) AlertDialog(onDismissRequest = { diff = false }, title = { Text("مراجعة التعديل") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                val before = original.lines()
                val after = content.lines()
                repeat(maxOf(before.size, after.size)) { index ->
                    val old = before.getOrNull(index)
                    val new = after.getOrNull(index)
                    if (old == new) Text("  ${old.orEmpty()}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    else {
                        if (old != null) Text("− $old", color = androidx.compose.ui.graphics.Color(0xFFFF9090), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (new != null) Text("+ $new", color = androidx.compose.ui.graphics.Color(0xFF8FDBA1), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { scope.launch {
            try {
                val savedHash = withContext(Dispatchers.IO) {
                    val file = requireNotNull(selected)
                    require(allowed(file) && ArtifactRepository.hash(file) == hash) { "تغير الملف؛ أعد قراءته قبل الحفظ" }
                    val relative = file.toRelativeString(root)
                    require(relative !in setOf("app/src/main/AndroidManifest.xml", "android/signing-cert-sha256.txt", "android/PROTECTED_PATHS", "scripts/build-signed-release.sh", "agent/agent.py") &&
                        !relative.startsWith(".github/") && !relative.startsWith("agent/m4core/"))
                    require(content.toByteArray().size <= 500000)
                    val backups = File(context.filesDir, "editor-backups").apply { mkdirs() }
                    file.copyTo(File(backups, UUID.randomUUID().toString()))
                    val temp = File(file.parentFile, ".m4-editor-" + UUID.randomUUID())
                    temp.writeText(content); require(temp.renameTo(file))
                    ArtifactRepository.hash(file)
                }
                document?.hash = savedHash; document?.original = content; diff = false; error = ""
            } catch (e: Exception) { error = "تعذر حفظ الملف: ${e.message}"; diff = false }
        } }) { Text("حفظ مع نسخة احتياطية") } }, dismissButton = { TextButton(onClick = { diff = false }) { Text("إلغاء") } })
}
