package com.musabgpt.m4.artifacts

import android.content.ClipData
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebView

@Composable
fun ArtifactChatCard(artifact: ChatArtifact) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    fun failure(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(artifact.mime)) { destination ->
        if (destination != null) {
            exporting = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val file = ArtifactRepository.file(context, artifact.id, artifact.name)
                        requireNotNull(context.contentResolver.openOutputStream(destination)).use { output ->
                            file.inputStream().use { it.copyTo(output) }
                        }
                        context.contentResolver.openInputStream(destination)?.use { input ->
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(16384)
                            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                            require(digest.digest().joinToString("") { "%02x".format(it) } == artifact.sha256)
                        } ?: error("تعذر التحقق من الملف المحفوظ")
                    }
                    Toast.makeText(context, "حُفظ الملف وتحققت بصمته", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) { failure("تعذر حفظ الملف: ${e.message}") }
                finally { exporting = false }
            }
        }
    }
    fun external(share: Boolean) {
        try {
            val uri = ArtifactRepository.uri(context, artifact)
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(artifact.mime).putExtra(Intent.EXTRA_STREAM, uri)
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, artifact.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.clipData = ClipData.newRawUri(artifact.name, uri)
            context.startActivity(if (share) Intent.createChooser(intent, "مشاركة المرفق") else intent)
        } catch (e: Exception) { failure("تعذر فتح المرفق: ${e.message}") }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(artifact.name, style = MaterialTheme.typography.titleMedium)
            Text("${artifact.mime} • ${artifact.size} بايت", style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = { scope.launch {
                    try {
                        preview = withContext(Dispatchers.IO) {
                            val file = ArtifactRepository.file(context, artifact.id, artifact.name)
                            if (artifact.mime == "application/zip") java.util.zip.ZipFile(file).use { zip ->
                                zip.entries().asSequence().take(1000).joinToString("\n") { "${it.name} (${it.size})" }
                            } else if (artifact.mime == "application/pdf") "PDF صالح؛ استخدم «فتح» لعرض الصفحات في عارض أندرويد."
                            else file.inputStream().bufferedReader().use { it.readText().take(50000) }
                        }
                    } catch (e: Exception) { failure("تعذرت المعاينة: ${e.message}") }
                } }) { Text("معاينة") }
                TextButton(onClick = { external(false) }) { Text("فتح") }
                TextButton(onClick = { exporter.launch(artifact.name) }, enabled = !exporting) { Text("حفظ") }
                TextButton(onClick = { external(true) }) { Text("مشاركة") }
            }
        }
    }
    preview?.let { value ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(artifact.name) }, text = {
            if (artifact.mime == "text/html") {
                AndroidView(factory = { ctx -> WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    loadDataWithBaseURL("https://preview.m4.invalid/", value, "text/html", "UTF-8", null)
                } }, modifier = Modifier.height(400.dp))
            } else Text(value, modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()))
        }, confirmButton = { TextButton(onClick = { preview = null }) { Text("إغلاق") } })
    }
}
