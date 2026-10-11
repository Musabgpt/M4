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
import androidx.compose.ui.unit.dp
import com.musabgpt.m4.artifacts.ArtifactRepository
import io.musab.arabicterminal.LinuxEnvironment
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

class WorkspaceEditorState {
    val documents = mutableStateListOf<EditorDocument>()
    var active by mutableStateOf<EditorDocument?>(null)
}

/** Bounded project editor with a content-hash conflict check and private backups. */
@Composable
fun FilesPanel(state: WorkspaceEditorState = remember { WorkspaceEditorState() }) {
    val context = LocalContext.current
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
    fun allowed(file: File): Boolean {
        val canonical = file.canonicalFile
        return canonical.toPath().startsWith(root.canonicalFile.toPath()) &&
            canonical.name !in setOf(".git", ".term-agent", ".env", ".ssh", ".aws") &&
            !canonical.name.endsWith(".pem") && !canonical.name.endsWith(".key")
    }
    LaunchedEffect(directory) {
        entries = withContext(Dispatchers.IO) { directory.listFiles().orEmpty().filter { allowed(it) }.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name }).take(250) }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
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
            if (!root.exists()) Text("شغّل أول مهمة للوكيل لإنشاء نسخة المشروع المدمجة")
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
                                    bytes.toString(Charsets.UTF_8)
                                }
                                val existing = state.documents.firstOrNull { it.file == file }
                                val opened = existing ?: EditorDocument(file, text, withContext(Dispatchers.IO) { ArtifactRepository.hash(file) }).also { state.documents.add(it) }
                                state.active = opened; error = ""
                            } catch (e: Exception) { error = "تعذرت قراءة الملف: ${e.message}" }
                        }
                    }) { Text((if (file.isDirectory) "▸ " else "") + file.name) }
                }
            }
        } else {
            Row {
                TextButton(onClick = { state.active = null }) { Text("رجوع") }
                TextButton(onClick = { diff = true }, enabled = original != content) { Text("مراجعة وحفظ") }
            }
            Row(Modifier.weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                Text((1..(content.count { it == '\n' } + 1)).joinToString("\n"),
                    modifier = Modifier.padding(top = 16.dp, end = 8.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(content, { document?.content = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, lineHeight = 22.sp),
                    modifier = Modifier.width(maxOf(700, (content.lineSequence().maxOfOrNull { it.length } ?: 0).coerceAtMost(500) * 9).dp),
                    placeholder = { Text("Write code…") })
            }
            Row {
                TextButton(onClick = { document?.content = original }, enabled = original != content) { Text("Discard changes") }
                TextButton(onClick = { state.documents.remove(document); state.active = state.documents.lastOrNull() }, enabled = original == content) { Text("Close tab") }
            }
        }
    }
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
                withContext(Dispatchers.IO) {
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
                    document?.hash = ArtifactRepository.hash(file)
                }
                document?.original = content; diff = false; error = ""
            } catch (e: Exception) { error = "تعذر حفظ الملف: ${e.message}"; diff = false }
        } }) { Text("حفظ مع نسخة احتياطية") } }, dismissButton = { TextButton(onClick = { diff = false }) { Text("إلغاء") } })
}
