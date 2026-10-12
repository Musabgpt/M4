package com.musabgpt.m4

import com.musabgpt.m4.runtime.InlineEditResponse
import com.musabgpt.m4.runtime.CodeHighlight
import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.*
import org.junit.Test

class InlineEditResponseTest {
    @Test fun preservesEscapesAndIndentation() {
        val source = "fun main() {\n    println(\"a\\nb\")\n}"
        assertEquals(source, InlineEditResponse.code("```kotlin\n$source\n```"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsProseInsteadOfReplacingAFile() { InlineEditResponse.code("Here is your code: missing output") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleCandidates() { InlineEditResponse.code("```\na\n```\n```\nb\n```") }
    @Test fun highlightingPreservesSourceAndSelectionOffsets() {
        val source = "val greeting = \"مرحبا\" // comment"
        val result = CodeHighlight.filter(AnnotatedString(source))
        assertEquals(source, result.text.text)
        for (index in 0..source.length) assertEquals(index, result.offsetMapping.originalToTransformed(index))
        assertTrue(result.text.spanStyles.isNotEmpty())
    }
    @Test fun undoRedoAndCompletionRespectCursor() {
        val doc = com.musabgpt.m4.runtime.EditorDocument(java.io.File("example.kt"), "", "")
        doc.update(androidx.compose.ui.text.input.TextFieldValue("pri", androidx.compose.ui.text.TextRange(3)))
        assertTrue(doc.suggestions().contains("private"))
        doc.complete("private")
        assertEquals("private", doc.content)
        doc.undo()
        assertEquals("pri", doc.content)
        doc.redo()
        assertEquals("private", doc.content)
        doc.find("private")
        assertEquals(androidx.compose.ui.text.TextRange(0, 7), doc.value.selection)
    }
    @Test fun chatMarkdownRendersBoldAndInlineCodeWithoutMarkers() {
        val value = inlineMarkdown("Created **calculator.html** with `HTML`.")
        assertEquals("Created calculator.html with HTML.", value.text)
        assertEquals(2, value.spanStyles.size)
    }
    @Test fun aiCursorCompletionPreservesSurroundingSource() {
        assertEquals("const answer = 42;", com.musabgpt.m4.runtime.applyCursorCompletion("const answer = ;", 15, 15, "42"))
        assertEquals("aNEWz", com.musabgpt.m4.runtime.applyCursorCompletion("aOLDz", 1, 4, "NEW"))
    }
}
