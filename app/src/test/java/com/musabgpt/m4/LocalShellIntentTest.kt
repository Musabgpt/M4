package com.musabgpt.m4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShellIntentTest {
    @Test fun localArabicFileCommandHasARealShellPlan() {
        assertEquals("shell:ls -la", M4NativeTools.quickAction("اعرض الملفات"))
        assertEquals("shell:pwd", M4NativeTools.quickAction("اين انا"))
    }

    @Test fun casualEnglishTextMustNotLaunchLinux() {
        assertNull(M4NativeTools.quickAction("hello world"))
    }

    @Test fun compoundWhatsAppMessageMustNotBeAShellOrApplicationName() {
        // Pure JVM Android tests cannot call Android's unmocked org.json.JSONObject.put.
        // Check the real local parser here; end-to-end action serialization needs instrumentation.
        val task=WhatsAppPlanner.parse("افتح واتساب وابعت رسالة مرحبا الى مروة")
        assertEquals("مروة",task?.recipient)
        assertEquals("مرحبا",task?.body)
        assertTrue(WhatsAppPlanner.looksLikeMessageRequest(
            "افتح واتساب وابعت رسالة مرحبا الى مروة"))
    }
}
