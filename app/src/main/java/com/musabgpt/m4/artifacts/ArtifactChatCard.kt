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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

@Composable
fun ArtifactChatCard(artifact: ChatArtifact) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(artifact.id) { onDispose { previewImage?.recycle() } }
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
                            if (artifact.mime.startsWith("image/")) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.absolutePath, bounds)
                                val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 1600).coerceAtLeast(1) }
                                previewImage?.recycle(); previewImage = requireNotNull(BitmapFactory.decodeFile(file.absolutePath, options)); "صورة المرفق"
                            } else if (artifact.mime == "application/pdf") {
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor -> PdfRenderer(descriptor).use { renderer ->
                                    require(renderer.pageCount > 0)
                                    renderer.openPage(0).use { page ->
                                        val scale = minOf(1200f / page.width, 1600f / page.height, 2f)
                                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                        bitmap.eraseColor(android.graphics.Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        previewImage?.recycle(); previewImage = bitmap
                                    }; "الصفحة الأولى من ${renderer.pageCount} صفحات"
                                } }
                            } else if (artifact.mime == "application/zip") java.util.zip.ZipFile(file).use { zip ->
                                zip.entries().asSequence().take(1000).joinToString("\n") { "${it.name} (${it.size})" }
                            } else file.inputStream().bufferedReader().use { reader -> val chars = CharArray(50000); val n = reader.read(chars); if (n < 0) "" else String(chars, 0, n) }
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
            if (previewImage != null) Column {
                Text(value)
                Image(previewImage!!.asImageBitmap(), contentDescription = artifact.name, modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp), contentScale = ContentScale.Fit)
            } else if (artifact.mime == "text/html") {
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
