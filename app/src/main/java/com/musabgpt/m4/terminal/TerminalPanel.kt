package com.musabgpt.m4.terminal

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun TerminalPanel(backend: EmbeddedAlpineBackend) {
    val state by backend.state.collectAsState()
    var input by remember { mutableStateOf("") }
    fun submit() {
        if (input.isNotBlank() && !state.preparing) {
            backend.execute(input)
            input = ""
        }
    }
    val outputScroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(state.frame?.text, follow) {
        if (follow) { kotlinx.coroutines.delay(16); outputScroll.scrollTo(outputScroll.maxValue) }
    }
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
            TextButton(onClick = { follow = !follow }) { Text(if (follow) "تثبيت العرض" else "متابعة المخرج") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(outputScroll).horizontalScroll(rememberScrollState())) {
            AndroidView(factory = { context ->
                TextView(context).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 12f
                    setTextColor(0xFFECF3FC.toInt())
                    setBackgroundColor(0xFF0B1422.toInt())
                    textDirection = View.TEXT_DIRECTION_LTR
                    setPadding(8, 8, 8, 8)
                    setTextIsSelectable(true)
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
                    val cursor = frame.cursorIndex - offset
                    if (cursor in 0 until text.length) text.setSpan(BackgroundColorSpan(0xFFB1C3D5.toInt()), cursor, cursor + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                view.text = text
            })
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf(
                "Esc" to "\u001b",
                "Tab" to "\t",
                "↑" to "\u001b[A",
                "↓" to "\u001b[B",
                "←" to "\u001b[D",
                "→" to "\u001b[C",
                "Ctrl+C" to "\u0003",
                "Ctrl+D" to "\u0004",
                "Ctrl+L" to "\u000c",
                "Ctrl+Z" to "\u001a",
                "Home" to "\u001b[H",
                "End" to "\u001b[F"
            ).forEach { (label, key) ->
                TextButton(onClick = { backend.sendInput(key) }) { Text(label) }
            }
        }
        Row {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("أمر أو إدخال REPL") },
                textStyle = TextStyle(textDirection = TextDirection.Ltr),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).testTag("terminal-command-input")
            )
            TextButton(
                onClick = { submit() },
                modifier = Modifier.testTag("terminal-command-send"),
                enabled = input.isNotBlank() && !state.preparing
            ) { Text("إرسال") }
        }
    }
}
