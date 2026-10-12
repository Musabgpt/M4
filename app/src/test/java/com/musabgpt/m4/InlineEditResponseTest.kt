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
}
