package com.musabgpt.m4.terminal

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TerminalPanel(backend: EmbeddedAlpineBackend) {
    val state by backend.state.collectAsState()
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Text(state.status, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            state.sessions.forEach { session ->
                TextButton(onClick = { backend.select(session) }) {
                    Text((if (session == state.selected) "● " else "") + session.name)
                }
            }
            TextButton(onClick = backend::newSession, enabled = !state.preparing) { Text("جلسة +") }
            TextButton(onClick = backend::interrupt, enabled = state.selected != null) { Text("مقاطعة") }
            TextButton(onClick = backend::closeSession, enabled = state.selected != null) { Text("إنهاء") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            AndroidView(factory = { context ->
                TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 12f
                    setTextColor(0xFFECF3FC.toInt())
                    setBackgroundColor(0xFF0B1422.toInt())
                    textDirection = View.TEXT_DIRECTION_LTR
                    setPadding(8, 8, 8, 8)
                }
            }, update = { view ->
                val frame = state.frame
                val raw = frame?.text ?: ""
                val offset = (raw.length - 24000).coerceAtLeast(0)
                val text = SpannableString(raw.substring(offset))
                if (frame != null) {
                    var start = offset
                    while (start < raw.length) {
                        var end = start + 1
                        while (end < raw.length && frame.foreground[end] == frame.foreground[start] &&
                            frame.background[end] == frame.background[start]) end++
                        text.setSpan(ForegroundColorSpan(frame.foreground[start]), start - offset, end - offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        text.setSpan(BackgroundColorSpan(frame.background[start]), start - offset, end - offset, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        start = end
                    }
                }
                view.text = text
            })
        }
        Row {
            listOf("Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B").forEach { (label, key) ->
                TextButton(onClick = { backend.sendInput(key) }) { Text(label) }
            }
        }
        Row {
            OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("أمر أو إدخال REPL") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { backend.execute(input); input = "" }, enabled = input.isNotBlank() && !state.preparing) { Text("إرسال") }
        }
    }
}
