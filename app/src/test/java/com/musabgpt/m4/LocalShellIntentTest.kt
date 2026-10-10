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
        val action=M4NativeTools.quickAction("افتح واتساب وابعت رسالة مرحبا الى مروة")
        assertTrue(action?.startsWith("whatsapp_draft:")==true)
    }
}
