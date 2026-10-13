package com.musabgpt.m4.runtime

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** Lexical highlighting only; source and cursor offsets remain unchanged. */
object CodeHighlight : VisualTransformation {
    private val tokens = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|//[^\\n]*|#[^\\n]*|\\b(?:fun|val|var|class|object|import|package|if|else|return|for|while|def|from|async|await|function|const|let|export|public|private|static|void|new|try|catch|throw|true|false|null|None|True|False)\\b|\\b[0-9]+(?:\\.[0-9]+)?\\b")
    override fun filter(text: AnnotatedString): TransformedText {
        val styled = AnnotatedString.Builder(text)
        tokens.findAll(text.text.take(50000)).forEach { match ->
            val token = match.value
            val color = when {
                token.startsWith("//") || token.startsWith("#") -> Color(0xFF7F9F7F)
                token.startsWith('"') || token.startsWith('\'') -> Color(0xFFCE9178)
                token.first().isDigit() -> Color(0xFFB5CEA8)
                else -> Color(0xFFC586C0)
            }
            styled.addStyle(SpanStyle(color = color), match.range.first, match.range.last + 1)
        }
        return TransformedText(styled.toAnnotatedString(), OffsetMapping.Identity)
    }
}

object InlineEditResponse {
    fun code(response: String): String {
        val trimmed = response.trim()
        require(trimmed.startsWith("```") && trimmed.endsWith("```")) { "لم يرجع النموذج مقطع كود واضحًا؛ لم يُغيّر الملف" }
        val firstLine = trimmed.indexOf('\n')
        require(firstLine >= 3 && firstLine < trimmed.length - 3)
        val code = trimmed.substring(firstLine + 1, trimmed.length - 3).removeSuffix("\n")
        require(code.isNotBlank() && code.length <= 100000 && !code.contains("```")) { "اقتراح غير صالح؛ لم يُغيّر الملف" }
        return code
    }
}
