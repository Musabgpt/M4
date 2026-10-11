package com.musabgpt.m4.artifacts

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import io.musab.arabicterminal.LinuxEnvironment
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class ChatArtifact(
    val id: String, val owner: String, val name: String, val mime: String,
    val size: Long, val sha256: String, val createdAt: Double
) {
    fun json() = JSONObject().put("artifact_id", id).put("owner_session_id", owner)
        .put("display_name", name).put("mime", mime).put("size_bytes", size)
        .put("sha256", sha256).put("created_at", createdAt)
}

/** Imports only verified immutable bytes from the known private Alpine namespace. */
object ArtifactRepository {
    private const val MAX_BYTES = 25_000_000L
    private val identifier = Regex("[a-f0-9]{32}")
    private fun root(context: Context) = File(context.filesDir, "artifacts").apply { mkdirs() }
    private fun validName(name: String) = name.isNotEmpty() && name.length <= 160 &&
        !name.startsWith(".") && name.none { it == '/' || it == '\\' || it == '\u0000' }

    private fun decode(obj: JSONObject) = ChatArtifact(
        obj.getString("artifact_id"), obj.getString("owner_session_id"),
        obj.getString("display_name"), obj.getString("mime"), obj.getLong("size_bytes"),
        obj.getString("sha256"), obj.optDouble("created_at", 0.0)
    )

    fun list(context: Context, owner: String): List<ChatArtifact> = root(context).listFiles().orEmpty()
        .mapNotNull { directory ->
            try {
                if (!identifier.matches(directory.name)) return@mapNotNull null
                val manifest = File(directory, "manifest.json")
                if (!manifest.isFile || manifest.length() > 32000) return@mapNotNull null
                val row = decode(JSONObject(manifest.readText()))
                if (row.owner != owner || row.id != directory.name || !validName(row.name)) null else row
            } catch (_: Exception) { null }
        }.sortedBy { it.createdAt }

    fun importPublished(context: Context) {
        val sourceRoot = File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent/artifacts")
        if (!sourceRoot.isDirectory) return
        sourceRoot.listFiles().orEmpty().sortedByDescending { it.lastModified() }.take(300).forEach { directory ->
            try {
                if (!identifier.matches(directory.name) || directory.canonicalFile.parentFile != sourceRoot.canonicalFile) return@forEach
                val manifest = File(directory, "manifest.json")
                if (!manifest.isFile || manifest.length() > 32000 || manifest.canonicalFile.parentFile != directory.canonicalFile) return@forEach
                val obj = JSONObject(manifest.readText())
                if (!obj.optBoolean("published")) return@forEach
                val row = decode(obj)
                if (row.id != directory.name || !validName(row.name) || row.size !in 1..MAX_BYTES) return@forEach
                val targetDirectory = File(root(context), row.id)
                if (File(targetDirectory, "manifest.json").isFile) return@forEach
                val source = File(directory, row.name)
                if (source.canonicalFile.parentFile != directory.canonicalFile || !source.isFile ||
                    source.length() != row.size || hash(source) != row.sha256) return@forEach
                targetDirectory.mkdirs()
                val target = File(targetDirectory, row.name)
                validateFormat(source, row.mime)
                val temporary = File(targetDirectory, ".import.tmp")
                source.copyTo(temporary, overwrite = true)
                require(hash(temporary) == row.sha256)
                require(temporary.renameTo(target))
                File(targetDirectory, "manifest.json").writeText(row.json().toString())
            } catch (_: Exception) { /* A partial/untrusted manifest never becomes a chat card. */ }
        }
    }

    fun file(context: Context, id: String, name: String): File {
        require(identifier.matches(id) && validName(name)) { "مرفق غير صالح" }
        val directory = File(root(context), id)
        require(directory.canonicalFile.parentFile == root(context).canonicalFile)
        val path = File(directory, name)
        require(path.canonicalFile.parentFile == directory.canonicalFile && path.isFile)
        val manifest = File(directory, "manifest.json")
        require(manifest.isFile && manifest.length() <= 32000)
        val row = decode(JSONObject(manifest.readText()))
        require(row.id == id && row.name == name && path.length() == row.size && row.size in 1..MAX_BYTES)
        require(hash(path) == row.sha256) { "تغيّرت بصمة المرفق" }
        return path
    }

    fun uri(context: Context, artifact: ChatArtifact): Uri = Uri.Builder()
        .scheme("content").authority(context.packageName + ".artifacts")
        .appendPath(artifact.id).appendPath(artifact.name).build()

    fun saveReport(context: Context, owner: String, name: String, text: String): ChatArtifact {
        require(validName(name) && text.length in 1..200000)
        val id = UUID.randomUUID().toString().replace("-", "")
        val directory = File(root(context), id).apply { mkdirs() }
        val file = File(directory, name).apply { writeText(text) }
        val row = ChatArtifact(id, owner, name, "text/markdown", file.length(), hash(file), System.currentTimeMillis() / 1000.0)
        File(directory, "manifest.json").writeText(row.json().toString())
        return row
    }

    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16384)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateFormat(file: File, mime: String) {
        if (mime == "application/pdf") {
            require(file.inputStream().use { val header = ByteArray(5); it.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-" })
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer -> require(renderer.pageCount > 0) }
            }
        } else if (mime == "application/zip") {
            java.util.zip.ZipFile(file).use { archive ->
                val entries = archive.entries()
                var size = 0L
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    require(++count <= 1000 && !entry.name.startsWith("/") &&
                        entry.name.split('/').none { it == ".." } && !entry.name.contains('\\'))
                    require(entry.size >= 0)
                    size += entry.size
                    require(size <= MAX_BYTES)
                    if (!entry.isDirectory) archive.getInputStream(entry).use { input ->
                        val crc = java.util.zip.CRC32()
                        val buffer = ByteArray(16384)
                        var actualSize = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break
                            actualSize += n; require(actualSize <= entry.size); crc.update(buffer, 0, n) }
                        require(actualSize == entry.size && crc.value == entry.crc)
                    }
                }
            }
        } else if (mime == "application/json") {
            org.json.JSONTokener(file.readText()).nextValue()
        }
    }

    /** Android shapes Arabic using platform fonts; opening/rendering verifies real PDF pages. */
    fun createPdf(context: Context, args: JSONObject): JSONObject {
        val id = args.getString("id")
        val name = args.getString("name")
        val content = args.getString("content")
        require(identifier.matches(id) && validName(name) && name.endsWith(".pdf"))
        require(content.isNotEmpty() && content.length <= 100000)
        val directory = File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent/artifacts/$id")
        require(directory.isDirectory)
        val base = File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent/artifacts").canonicalFile
        require(directory.canonicalFile.parentFile == base)
        val output = File(directory, name)
        require(!output.exists())
        val paint = TextPaint().apply { textSize = 14f; color = android.graphics.Color.BLACK; typeface = Typeface.DEFAULT }
        val layout = StaticLayout.Builder.obtain(content, 0, content.length, paint, 515)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setIncludePad(true).build()
        val pdf = PdfDocument()
        try {
            var line = 0
            var pageNumber = 0
            while (line < layout.lineCount) {
                require(++pageNumber <= 100) { "PDF يتجاوز مئة صفحة" }
                val top = layout.getLineTop(line)
                var end = line + 1
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= 762) end++
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                page.canvas.save()
                page.canvas.clipRect(40, 40, 555, 40 + layout.getLineTop(end) - top)
                page.canvas.translate(40f, 40f - top)
                layout.draw(page.canvas)
                page.canvas.restore()
                pdf.finishPage(page)
                line = end
            }
            output.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        validateFormat(output, "application/pdf")
        var pages = 0
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                pages = renderer.pageCount
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.recycle()
                }
            }
        }
        return JSONObject().put("pages", pages).put("size_bytes", output.length()).put("sha256", hash(output))
    }
}
