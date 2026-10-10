package com.musabgpt.m4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun simpleLaunchIsNotMessageTask() {
        assertNull(WhatsAppPlanner.parse("افتح واتساب"))
        assertNull(WhatsAppPlanner.parse("افتح تطبيق واتساب"))
    }
}
