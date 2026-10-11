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

data class RuntimeEvent(val id: Long, val kind: String, val tool: String, val arguments: String, val result: String)

object ChatEventStream {
    fun read(context: Context, owner: String): List<RuntimeEvent> {
        val file = File(LinuxEnvironment.rootfs(context), "root/projects/M4/.term-agent/events.jsonl")
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
                    if (row.optString("owner_session_id") != owner) null
                    else {
                        val data = row.optJSONObject("data")
                        RuntimeEvent(row.getLong("id"), row.optString("kind"), data?.optString("tool", "") ?: "",
                            data?.optJSONObject("args")?.toString()?.take(3000) ?: "",
                            data?.optString("result", "")?.take(7000) ?: row.opt("data").toString().take(7000))
                    }
                } catch (_: Exception) { null }
            }.toList().takeLast(20)
        } catch (_: Exception) { emptyList() }
    }
}

@Composable
fun InteractiveToolCard(event: RuntimeEvent) {
    var expanded by remember(event.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text((if (expanded) "▾ " else "▸ ") + (event.tool.ifBlank { event.kind }) + " • #${event.id}")
            }
            if (expanded) {
                if (event.arguments.isNotBlank()) Text(event.arguments, style = MaterialTheme.typography.labelSmall)
                Text(event.result, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
