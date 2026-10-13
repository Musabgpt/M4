package com.musabgpt.m4.runtime

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.musab.arabicterminal.LinuxEnvironment
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import android.database.sqlite.SQLiteDatabase
import android.content.ContentValues

data class RuntimeEvent(val id: Long, val kind: String, val tool: String, val arguments: String, val result: String,
    val turn: String = "", val time: Double = 0.0, val ok: Boolean? = null, val callId: String = "")

object ChatEventStream {
    fun read(context: Context, owner: String, turn: String? = null, limit: Int = 30): List<RuntimeEvent> {
        val files = listOf(File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent/events.jsonl"),
            File(context.filesDir, "native-chat-events/events.jsonl"))
        val maximum = limit.coerceIn(1, 5000)
        return files.flatMap { file -> readDatabase(File(file.parentFile, "events.sqlite3"), owner, turn, maximum)
            .ifEmpty { readFile(file, owner, turn) } }.sortedBy { it.time }.takeLast(maximum)
    }

    private fun readDatabase(file: File, owner: String, turn: String?, limit: Int): List<RuntimeEvent> {
        if (!file.isFile) return emptyList()
        return try { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id,time,kind,data FROM events WHERE owner=? ORDER BY time DESC LIMIT ?", arrayOf(owner, limit.toString())).use { cursor ->
                buildList { while (cursor.moveToNext()) {
                    val data = JSONObject(cursor.getString(3))
                    if (turn != null && data.optString("turn_id") != turn) continue
                    val kind = cursor.getString(2)
                    add(RuntimeEvent(if (file.parentFile?.name == "native-chat-events") -cursor.getLong(0) else cursor.getLong(0), kind,
                        data.optString("tool"), data.optJSONObject("args")?.toString()?.take(3000).orEmpty(),
                        (if (kind == "token") data.optString("text") else if (data.has("result")) data.optString("result") else data.toString()).take(12000),
                        data.optString("turn_id"), cursor.getDouble(1), if (data.has("ok")) data.optBoolean("ok") else null, data.optString("call_id")))
                } }
            }
        } } catch (_: Exception) { emptyList() }
    }

    private fun readFile(file: File, owner: String, turn: String?): List<RuntimeEvent> {
        if (!file.isFile) return emptyList()
        return try {
            val text = RandomAccessFile(file, "r").use { stream ->
                val offset = (stream.length() - 128000).coerceAtLeast(0)
                stream.seek(offset)
                val bytes = ByteArray((stream.length() - offset).toInt().coerceAtMost(128000))
                stream.readFully(bytes)
                val content = String(bytes, Charsets.UTF_8)
                if (offset > 0) content.substringAfter('\n', "") else content
            }
            text.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                try {
                    val row = JSONObject(line)
                    if (row.optString("owner_session_id") != owner ||
                        (turn != null && row.optJSONObject("data")?.optString("turn_id") != turn)) null
                    else {
                        val data = row.optJSONObject("data")
                        RuntimeEvent(row.getLong("id"), row.optString("kind"), data?.optString("tool", "") ?: "",
                            data?.optJSONObject("args")?.toString()?.take(3000) ?: "",
                            if (row.optString("kind") == "token") data?.optString("text", "").orEmpty()
                            else (if (data?.has("result") == true) data.optString("result") else data?.toString().orEmpty()).take(7000),
                            data?.optString("turn_id", "").orEmpty(), row.optDouble("time", 0.0),
                            if (data?.has("ok") == true) data.optBoolean("ok") else null, data?.optString("call_id").orEmpty())
                    }
                } catch (_: Exception) { null }
            }.toList()
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun append(context: Context, owner: String, turn: String, kind: String, data: JSONObject) {
        require(owner.matches(Regex("[A-Za-z0-9-]{1,80}")))
        require(turn.matches(Regex("[A-Za-z0-9-]{1,80}")))
        // Native actions can run before Alpine has been installed. Never create
        // an unseeded project directory that would prevent Git bootstrap.
        val directory = File(context.filesDir, "native-chat-events").apply { mkdirs() }
        val now = System.currentTimeMillis() / 1000.0
        data.put("turn_id", turn)
        val id = SQLiteDatabase.openOrCreateDatabase(File(directory, "events.sqlite3"), null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY,time REAL,kind TEXT,data TEXT,owner TEXT)")
            db.insertOrThrow("events", null, ContentValues().apply { put("time", now); put("kind", kind); put("data", data.toString()); put("owner", owner) })
        }
        val journal = File(directory, "events.jsonl")
        if (journal.length() > 5_000_000) journal.renameTo(File(directory, "events.previous.jsonl"))
        journal.appendText(JSONObject().put("id", -id).put("time", now).put("kind", kind)
            .put("data", data).put("owner_session_id", owner).toString() + "\n")
    }
}

@Composable
fun InteractiveToolCard(event: RuntimeEvent) {
    val pendingResult = runCatching { JSONObject(event.result).optString("status") in setOf("running", "starting", "pending_result") }.getOrDefault(false)
    var expanded by remember(event.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text((if (expanded) "▾ " else "▸ ") + (event.tool.ifBlank { event.kind }) +
                    (if (pendingResult) " ⏳" else when (event.ok) { true -> " ✓"; false -> " ✗"; null -> if (event.kind == "tool_started") " ⏳" else "" }) + " • #${event.id}")
            }
            if (expanded) {
                if (event.arguments.isNotBlank()) Text(event.arguments, style = MaterialTheme.typography.labelSmall)
                Text(event.result, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
