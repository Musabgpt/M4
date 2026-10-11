package com.musabgpt.m4.runtime

import androidx.compose.foundation.layout.*
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

/** Bounded project editor with a content-hash conflict check and private backups. */
@Composable
fun FilesPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = remember { File(LinuxEnvironment.rootfs(context), "root/projects/M4") }
    var directory by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf(emptyList<File>()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf(false) }
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
        Text("ملفات المشروع", style = MaterialTheme.typography.titleMedium)
        Text(directory.toRelativeString(root).ifBlank { "/root/projects/M4" }, style = MaterialTheme.typography.labelSmall)
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (selected == null) {
            if (directory != root) TextButton(onClick = { directory = directory.parentFile ?: root }) { Text("المجلد الأعلى") }
            if (!root.exists()) Text("شغّل أول مهمة للوكيل لإنشاء نسخة المشروع المدمجة")
            LazyColumn {
                items(entries, key = { it.absolutePath }) { file ->
                    TextButton(onClick = {
                        if (file.isDirectory) directory = file
                        else scope.launch {
                            try {
                                val text = withContext(Dispatchers.IO) {
                                    require(allowed(file) && file.length() <= 250000)
                                    val bytes = file.readBytes(); require(bytes.none { it == 0.toByte() })
                                    bytes.toString(Charsets.UTF_8)
                                }
                                selected = file; content = text; original = text; hash = withContext(Dispatchers.IO) { ArtifactRepository.hash(file) }; error = ""
                            } catch (e: Exception) { error = "تعذرت قراءة الملف: ${e.message}" }
                        }
                    }) { Text((if (file.isDirectory) "▸ " else "") + file.name) }
                }
            }
        } else {
            Row {
                TextButton(onClick = { selected = null }) { Text("رجوع") }
                TextButton(onClick = { diff = true }, enabled = original != content) { Text("مراجعة وحفظ") }
            }
            OutlinedTextField(content, { content = it }, label = { Text(selected!!.name) }, modifier = Modifier.fillMaxSize())
        }
    }
    if (diff) AlertDialog(onDismissRequest = { diff = false }, title = { Text("مراجعة التعديل") },
        text = { Text("قبل:\n${original.take(2500)}\n\nبعد:\n${content.take(2500)}") },
        confirmButton = { TextButton(onClick = { scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val file = requireNotNull(selected)
                    require(allowed(file) && ArtifactRepository.hash(file) == hash) { "تغير الملف؛ أعد قراءته قبل الحفظ" }
                    val relative = file.toRelativeString(root)
                    require(relative !in setOf("app/src/main/AndroidManifest.xml", "android/signing-cert-sha256.txt", "android/PROTECTED_PATHS", "scripts/build-signed-release.sh") && !relative.startsWith(".github/"))
                    require(content.toByteArray().size <= 500000)
                    val backups = File(context.filesDir, "editor-backups").apply { mkdirs() }
                    file.copyTo(File(backups, UUID.randomUUID().toString()))
                    val temp = File(file.parentFile, ".m4-editor-" + UUID.randomUUID())
                    temp.writeText(content); require(temp.renameTo(file))
                    hash = ArtifactRepository.hash(file)
                }
                original = content; diff = false; error = ""
            } catch (e: Exception) { error = "تعذر حفظ الملف: ${e.message}"; diff = false }
        } }) { Text("حفظ مع نسخة احتياطية") } }, dismissButton = { TextButton(onClick = { diff = false }) { Text("إلغاء") } })
}
