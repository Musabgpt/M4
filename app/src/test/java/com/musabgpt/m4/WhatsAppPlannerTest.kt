package com.musabgpt.m4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppPlannerTest {
    @Test fun separatesCompoundMessageFromAppName() {
        val task=WhatsAppPlanner.parse("افتح واتساب وابعت رسالة مرحبا الى مروة")
        assertEquals("مروة", task?.recipient)
        assertEquals("مرحبا", task?.body)
    }

    @Test fun understandsSendVerbVariants() {
        val task=WhatsAppPlanner.parse("افتح واتساب وارسل رسالة السلام عليكم إلى مروة")
        assertEquals("مروة", task?.recipient)
        assertEquals("السلام عليكم", task?.body)
    }

    @Test fun typoSendVerbStillMeansWhatsAppDraft() {
        val task=WhatsAppPlanner.parse("افتح واتساب واريل رسالة مرحبا الى مروة")
        assertEquals("مروة",task?.recipient)
        assertEquals("مرحبا",task?.body)
    }

    @Test fun neverTreatCompoundWhatsAppInstructionAsAnAppName() {
        assertTrue(WhatsAppPlanner.looksLikeMessageRequest(
            "افتح واتساب وابعت رسالة مرحبا الى مروة"))
        assertTrue(WhatsAppPlanner.looksLikeMessageRequest(
            "افتح واتساب وابعت رسالة"))
    }

    @Test fun simpleLaunchIsNotMessageTask() {
        assertNull(WhatsAppPlanner.parse("افتح واتساب"))
        assertNull(WhatsAppPlanner.parse("افتح تطبيق واتساب"))
    }
}
