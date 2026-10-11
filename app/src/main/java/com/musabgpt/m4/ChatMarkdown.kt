package com.musabgpt.m4

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Render Markdown data directly; no remote HTML or JavaScript renderer. */
@Composable
fun ChatMarkdown(source: String) {
    val lines = source.lines()
    val blocks = mutableListOf<Pair<Boolean, List<String>>>()
    var i = 0
    while (i < lines.size) {
        val table = i + 1 < lines.size && lines[i].contains('|') && lines[i + 1].trim().matches(Regex("[| :\\-]+")) && lines[i + 1].contains('-')
        if (table) {
            val rows = mutableListOf(lines[i]); i += 2
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows += lines[i++]
            blocks += true to rows
        } else blocks += false to listOf(lines[i++])
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { (table, rowLines) ->
            if (table) Surface(tonalElevation = 2.dp) {
                Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                    rowLines.forEachIndexed { index, line ->
                        Row { line.trim().trim('|').split('|').forEach { cell ->
                            Text(cell.trim(), fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.widthIn(min = 120.dp, max = 260.dp).padding(6.dp))
                        } }
                        HorizontalDivider()
                    }
                }
            } else {
                val line = rowLines.first()
                val heading = Regex("^(#{1,6})\\s+(.*)").matchEntire(line)
                val text = heading?.groupValues?.get(2) ?: if (line.startsWith("- ") || line.startsWith("* ")) "• " + line.drop(2) else line.removePrefix("> ")
                val links = Regex("\\[([^]]+)\\]\\((https?://[^ )]+)\\)")
                val annotated = buildAnnotatedString {
                    var offset = 0
                    links.findAll(text).forEach { link ->
                        append(text.substring(offset, link.range.first))
                        pushStringAnnotation("url", link.groupValues[2])
                        withStyle(SpanStyle(color = Color(0xFFD97757))) { append(link.groupValues[1]) }
                        pop(); offset = link.range.last + 1
                    }; append(text.substring(offset))
                }
                val uri = LocalUriHandler.current
                SelectionContainer { ClickableText(annotated,
                    style = (if (heading != null) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.bodyLarge).copy(color = MaterialTheme.colorScheme.onSurface),
                    onClick = { pos -> annotated.getStringAnnotations("url", pos, pos).firstOrNull()?.let { runCatching { uri.openUri(it.item) } } }) }
            }
        }
    }
}

fun highlightedCode(code: String): AnnotatedString = buildAnnotatedString {
    val pattern = Regex("(?m)(#[^\\n]*|//[^\\n]*|\"[^\"\\n]*\"|'[^'\\n]*'|\\b(?:def|class|import|from|return|if|else|for|while|const|let|var|function|fun|val|true|false|null|None|print)\\b|\\b[0-9]+\\b)")
    var offset = 0
    pattern.findAll(code).take(5000).forEach { match ->
        append(code.substring(offset, match.range.first))
        val color = when { match.value.startsWith('#') || match.value.startsWith("//") -> Color(0xFF8D9F88); match.value.startsWith('"') || match.value.startsWith('\'') -> Color(0xFFC0A875); else -> Color(0xFFD97757) }
        withStyle(SpanStyle(color = color)) { append(match.value) }; offset = match.range.last + 1
    }; append(code.substring(offset))
}
